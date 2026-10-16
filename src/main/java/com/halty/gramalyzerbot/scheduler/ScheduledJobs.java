package com.halty.gramalyzerbot.scheduler;

import com.halty.gramalyzerbot.analytics.Report;
import com.halty.gramalyzerbot.analytics.Schedule;
import com.halty.gramalyzerbot.bot.BotApi;
import com.halty.gramalyzerbot.bot.screen.DigestBuilder;
import com.halty.gramalyzerbot.bot.ui.Btn;
import com.halty.gramalyzerbot.bot.ui.Cb;
import com.halty.gramalyzerbot.bot.ui.Fmt;
import com.halty.gramalyzerbot.bot.ui.Labels;
import com.halty.gramalyzerbot.bot.ui.View;
import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.channel.ChannelService;
import com.halty.gramalyzerbot.channel.MembershipService;
import com.halty.gramalyzerbot.channel.SubscriberService;
import com.halty.gramalyzerbot.post.AnalysisService;
import com.halty.gramalyzerbot.post.Post;
import com.halty.gramalyzerbot.post.PostService;
import com.halty.gramalyzerbot.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Background work: subscriber snapshots, weekly digests and "good time to post" nudges.
 * Only runs when Telegram polling is on; without a bot there is nobody to message.
 */
@Component
@ConditionalOnProperty(name = "telegram.bot.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class ScheduledJobs {

    private final ChannelService channels;
    private final SubscriberService subscribers;
    private final MembershipService membership;
    private final AnalysisService analysis;
    private final PostService posts;
    private final DigestBuilder digests;
    private final BotApi gateway;

    @Scheduled(fixedDelayString = "PT6H", initialDelayString = "PT1M")
    public void snapshotSubscribers() {
        for (Channel ch : channels.trackable()) {
            try {
                subscribers.refresh(ch);
                pause();
            } catch (RuntimeException e) {
                log.warn("Subscriber snapshot failed for channel {}", ch.getId(), e);
            }
        }
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT2M")
    public void tick() {
        Instant now = Instant.now();
        for (Channel ch : channels.trackable()) {
            try {
                digest(ch, now);
                nudge(ch, now);
            } catch (RuntimeException e) {
                log.warn("Scheduled work failed for channel {}", ch.getId(), e);
            }
        }
    }

    private void digest(Channel ch, Instant now) {
        ZonedDateTime local = now.atZone(ch.zone());
        if (!Schedule.digestDue(local, ch.getDigestDay(), ch.getDigestHour(), ch.getLastDigestAt(), now)) return;
        ch.setLastDigestAt(now); // mark first so a slow send can never double-fire
        ch = channels.save(ch);
        if (posts.latest(ch).isEmpty()) return;
        View view = digests.build(ch);
        for (User u : membership.recipients(ch, false)) {
            gateway.send(u.getTelegramId(), view);
            pause();
        }
    }

    private void nudge(Channel ch, Instant now) {
        List<User> recipients = membership.recipients(ch, true);
        if (recipients.isEmpty()) return;
        ZonedDateTime local = now.atZone(ch.zone());
        Report report = analysis.report(ch);
        Instant lastPost = posts.latest(ch).map(Post::getPostedAt).orElse(null);
        Optional<Report.Slot> due = Schedule.nudgeDue(report, local, ch.getNudgeLeadMinutes(), ch.getNudgeGapHours(),
                lastPost, ch.getLastNudgeAt(), now);
        if (due.isEmpty()) return;

        ch.setLastNudgeAt(now);
        ch = channels.save(ch);
        Report.Slot slot = due.get();
        View v = View.of("⏰ <b>Good time to post!</b>")
                .line("<b>" + Fmt.esc(ch.getTitle()) + "</b>: " + Labels.slot(slot) + " is one of your best slots (" + Fmt.lift(slot.lift()) + " typical engagement).")
                .line("Last post: " + (lastPost == null ? "none yet" : Fmt.ago(lastPost, now)) + ".")
                .row(Btn.cb("💡 What to write", Cb.of("nav:content", ch.getId(), ch.getWindowDays(), "main")));
        for (User u : recipients) {
            gateway.send(u.getTelegramId(), v);
            pause();
        }
    }

    /** Stay well under Telegram's broadcast limits (~30 messages/second). */
    private static void pause() {
        try {
            Thread.sleep(60);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
