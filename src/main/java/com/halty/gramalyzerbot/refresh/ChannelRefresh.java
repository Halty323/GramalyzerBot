package com.halty.gramalyzerbot.refresh;

import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.channel.ChannelService;
import com.halty.gramalyzerbot.channel.SubscriberService;
import com.halty.gramalyzerbot.tracking.TrackedChannel;
import com.halty.gramalyzerbot.tracking.TrackingService;
import com.halty.gramalyzerbot.views.ViewSpeedJobs;
import com.halty.gramalyzerbot.workspace.PostingWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Brings every managed channel (the bot's and the website's own) and every watched public channel up to date in one
 * pass. It runs when the server starts, when it is asked to stop (before the database closes, within a time budget so
 * {@code docker stop} never has to kill it), and when the website or Studio opens or closes ({@code POST /api/refresh}).
 * <p>
 * One failing channel never stops the others: it keeps its last good data and the run moves on. Whatever doesn't fit
 * into the shutdown budget is read first on the next start, because channels are visited stalest first.
 */
@Component
@Slf4j
public class ChannelRefresh implements SmartLifecycle {

    /** A channel read this recently counts as up to date, so opening and closing an app in a row reads nothing twice. */
    static final Duration FRESH_ENOUGH = Duration.ofMinutes(2);

    public record Run(String reason, Instant startedAt, Instant finishedAt, int channels, int refreshed, int failed, int left) {}

    public record Status(boolean running, Run current, Run last) {}

    private final TrackingService tracking;
    private final ChannelService channels;
    private final ObjectProvider<SubscriberService> subscribers;
    private final ObjectProvider<PostingWorker> worker;
    private final ObjectProvider<ViewSpeedJobs> views;
    private final boolean enabled;
    private final boolean bot;
    private final boolean onStart;
    private final boolean onStop;
    private final Duration stopBudget;
    private final Duration cooldown;
    private final Duration pause;

    private final ReentrantLock lock = new ReentrantLock();
    private final ExecutorService runner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "channel-refresh");
        t.setDaemon(true);
        return t;
    });
    private volatile Instant deadline = Instant.MAX;
    private volatile Progress current;
    private volatile Run last;
    private volatile boolean running;

    public ChannelRefresh(TrackingService tracking, ChannelService channels, ObjectProvider<SubscriberService> subscribers,
                          ObjectProvider<PostingWorker> worker, ObjectProvider<ViewSpeedJobs> views,
                          @Value("${gramalyzer.tracking.refresh-enabled:true}") boolean enabled,
                          @Value("${telegram.bot.enabled:false}") boolean bot,
                          @Value("${gramalyzer.refresh.on-start:true}") boolean onStart,
                          @Value("${gramalyzer.refresh.on-stop:true}") boolean onStop,
                          @Value("${gramalyzer.refresh.stop-budget:PT25S}") Duration stopBudget,
                          @Value("${gramalyzer.refresh.cooldown:PT1M}") Duration cooldown,
                          @Value("${gramalyzer.refresh.pause:PT1.5S}") Duration pause) {
        this.tracking = tracking;
        this.channels = channels;
        this.subscribers = subscribers;
        this.worker = worker;
        this.views = views;
        this.enabled = enabled;
        this.bot = bot;
        this.onStart = onStart;
        this.onStop = onStop;
        this.stopBudget = stopBudget;
        this.cooldown = cooldown;
        this.pause = pause;
    }

    public Status status() {
        Progress p = current;
        return new Status(p != null, p == null ? null : p.snapshot(null), last);
    }

    /**
     * Starts a pass in the background unless one is running or one finished within the cooldown (an app that opens and
     * closes in a loop must not hammer t.me). Returns at once.
     */
    public Status request(String reason) {
        Run done = last;
        if (enabled && running && current == null && (done == null || done.finishedAt().isBefore(Instant.now().minus(cooldown)))) {
            runner.execute(() -> runNow(reason, Instant.MAX));
            return new Status(true, null, done); // queued: the caller can wait for it to finish
        }
        return status();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (enabled && onStart) runner.execute(() -> runNow("start", Instant.MAX));
    }

    /** One pass, now, on the calling thread. Waits for a pass already running instead of starting a second one. */
    public Run runNow(String reason, Instant until) {
        lock.lock();
        try {
            deadline = until;
            Progress p = new Progress(reason);
            current = p;
            try {
                refreshAll(p);
            } finally {
                current = null;
                last = p.snapshot(Instant.now());
            }
            log.info("Channel refresh ({}): {} of {} channels up to date, {} failed, {} left for next time",
                    reason, last.refreshed(), last.channels(), last.failed(), last.left());
            return last;
        } finally {
            lock.unlock();
        }
    }

    private void refreshAll(Progress p) {
        // the website's channels: member counts straight from Telegram (the worker skips this without a bot)
        PostingWorker w = worker.getIfAvailable();
        if (w != null) safely(w::refreshSubscribers);

        // the bot's channels: member counts and the subscriber history
        List<Channel> own = channels.trackable();
        SubscriberService subs = bot ? subscribers.getIfAvailable() : null;
        if (subs != null) {
            p.channels += own.size();
            for (Channel ch : own) {
                if (outOfTime()) { p.left++; continue; }
                try {
                    subs.refresh(ch);
                    p.refreshed++;
                } catch (RuntimeException e) {
                    p.failed++;
                    log.warn("Refreshing channel {} failed: {}", ch.getId(), e.toString());
                }
            }
        }

        // views of own posts that just passed their 1, 6 or 24 hour mark
        ViewSpeedJobs v = views.getIfAvailable();
        if (v != null && !outOfTime()) safely(v::readDue);

        // every public channel anyone watches, own ones included, stalest first: posts, views and subscribers
        List<TrackedChannel> watched = tracking.watchedAnywhere().stream().filter(c -> !c.isDemo())
                .sorted(Comparator.comparing(TrackedChannel::getFetchedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
        p.channels += watched.size();
        for (TrackedChannel c : watched) {
            if (outOfTime()) { p.left++; continue; }
            boolean stale = c.getFetchedAt() == null || c.getLastError() != null
                    || c.getFetchedAt().isBefore(Instant.now().minus(FRESH_ENOUGH));
            try {
                TrackedChannel after = tracking.refreshIfOlder(c, FRESH_ENOUGH);
                if (after.getLastError() == null) p.refreshed++; else p.failed++;
            } catch (RuntimeException e) {
                p.failed++;
                log.warn("Refreshing @{} failed: {}", c.getUsername(), e.toString());
            }
            if (stale) sleep(); // t.me is a public website, not an API
        }
    }

    private boolean outOfTime() {
        return Instant.now().isAfter(deadline) || Thread.currentThread().isInterrupted();
    }

    private void sleep() {
        long ms = pause.toMillis();
        Instant until = deadline;
        if (!until.equals(Instant.MAX)) ms = Math.min(ms, Math.max(0, until.toEpochMilli() - System.currentTimeMillis()));
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void safely(Runnable job) {
        try {
            job.run();
        } catch (RuntimeException e) {
            log.warn("Refresh step failed: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------ lifecycle: the last pass before shutdown

    @Override
    public void start() {
        running = true;
    }

    /**
     * Runs before the web server, the scheduler and the database shut down (highest phase stops first). A pass still
     * running from startup is cut short to the budget; then one last pass uses whatever time remains.
     */
    @Override
    public void stop() {
        running = false;
        if (!enabled || !onStop) { runner.shutdownNow(); return; }
        Instant until = Instant.now().plus(stopBudget);
        if (current != null && deadline.isAfter(until)) deadline = until;
        try {
            if (lock.tryLock(stopBudget.toMillis(), TimeUnit.MILLISECONDS)) {
                try {
                    if (Instant.now().isBefore(until)) runNow("stop", until);
                } finally {
                    lock.unlock();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            runner.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    private static final class Progress {
        final String reason;
        final Instant startedAt = Instant.now();
        volatile int channels, refreshed, failed, left;

        Progress(String reason) { this.reason = reason; }

        Run snapshot(Instant finishedAt) { return new Run(reason, startedAt, finishedAt, channels, refreshed, failed, left); }
    }
}
