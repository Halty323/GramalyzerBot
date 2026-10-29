package com.halty.gramalyzerbot.views;

import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.channel.Links;
import com.halty.gramalyzerbot.post.Post;
import com.halty.gramalyzerbot.post.PostRepository;
import com.halty.gramalyzerbot.tracking.PreviewParser.PreviewPost;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/**
 * How fast an own channel's posts gather views, and which new post is falling behind the channel's usual pace or
 * running well ahead of it.
 * <p>
 * The Bot API has no view counts, so they come from a public channel's web preview. Each post keeps its views 1, 6 and
 * 24 hours after posting ("marks"), filled in from the readings either side of a mark. The usual pace is the median of
 * the channel's recent posts at each mark.
 */
@Service
@RequiredArgsConstructor
public class ViewSpeedService {

    public enum Mark {
        H1(Duration.ofHours(1), Post::getViews1h, Post::setViews1h),
        H6(Duration.ofHours(6), Post::getViews6h, Post::setViews6h),
        H24(Duration.ofHours(24), Post::getViews24h, Post::setViews24h);

        final Duration after;
        final Function<Post, Integer> get;
        final java.util.function.BiConsumer<Post, Integer> set;

        Mark(Duration after, Function<Post, Integer> get, java.util.function.BiConsumer<Post, Integer> set) {
            this.after = after;
            this.get = get;
            this.set = set;
        }

        public Duration after() { return after; }

        public Integer of(Post p) { return get.apply(p); }
    }

    /** Behind this share of the usual pace, a post is lagging. */
    static final double LAG = 0.6;
    /** At this multiple of the usual pace or more, a post is flying. */
    static final double FAST = 1.8;
    /** The usual pace needs this many earlier posts with a reading at the mark. */
    static final int MIN_HISTORY = 5;
    static final int HISTORY = 30;
    static final int RECENT = 12;
    /** Posts this young still get readings. */
    static final Duration TRACKED_FOR = Duration.ofHours(25);

    private final PostRepository posts;
    private final ApplicationEventPublisher events;

    /** The usual views at each mark, null where there isn't enough history. */
    public record Pace(Integer h1, Integer h6, Integer h24) {
        public Integer at(Mark m) { return switch (m) { case H1 -> h1; case H6 -> h6; case H24 -> h24; }; }
    }

    /** A post that is lagging (or flying) at a mark, for the team to hear about. */
    public record Alert(Channel channel, Post post, Mark mark, int views, int usual, double ratio, boolean lagging) {}

    /**
     * @param status early (under an hour old), lag, fast, normal, or unknown (no reading)
     * @param ratio  views at the latest mark passed, over the usual views there
     */
    public record PostSpeed(int messageId, Instant postedAt, String kind, String preview, String url, Integer views,
                            Integer h1, Integer h6, Integer h24, String status, Double ratio) {}

    /** @param available whether views can be read at all: the channel is public (or a sample) */
    public record Report(boolean available, Pace usual, List<PostSpeed> posts) {}

    // ------------------------------------------------------------------ readings

    /** Whether the channel has a post that has just passed a mark without a reading after it. */
    public boolean due(Channel ch, Instant now) {
        for (Post p : posts.findAllByChannelIdAndPostedAtAfterOrderByPostedAtAsc(ch.getId(), now.minus(TRACKED_FOR)))
            for (Mark m : Mark.values())
                if (m.of(p) == null && !p.getPostedAt().plus(m.after).isAfter(now) && !readAfter(p, p.getPostedAt().plus(m.after))) return true;
        return false;
    }

    private static boolean readAfter(Post p, Instant at) { return p.getViewsCheckedAt() != null && !p.getViewsCheckedAt().isBefore(at); }

    /** Stores the views the web preview shows for the channel's posts; returns the alerts this reading set off. */
    @Transactional
    public List<Alert> record(Channel ch, List<PreviewPost> page, Instant now) {
        Map<Integer, Integer> seen = new HashMap<>();
        for (PreviewPost pp : page) if (pp.views() != null) seen.put(pp.messageId(), pp.views());
        if (seen.isEmpty()) return List.of();
        List<Post> changed = new ArrayList<>();
        List<Alert> alerts = new ArrayList<>();
        for (Post p : posts.findAllByChannelIdAndMessageIdIn(ch.getId(), seen.keySet())) {
            int views = seen.get(p.getMessageId());
            List<Mark> filled = fill(p, views, now);
            p.setViews(views);
            p.setViewsCheckedAt(now);
            changed.add(p);
            if (p.getPaceAlert() != null) continue;
            for (Mark m : filled) {
                if (m == Mark.H24) continue; // a day in, it's too late to help the post
                Integer typical = pace(ch, p).at(m);
                if (typical == null || typical == 0) continue;
                double ratio = m.of(p) / (double) typical;
                if (ratio < LAG || ratio >= FAST) {
                    p.setPaceAlert(ratio < LAG ? "LAG" : "FAST");
                    alerts.add(new Alert(ch, p, m, m.of(p), typical, ratio, ratio < LAG));
                    break;
                }
            }
        }
        posts.saveAll(changed);
        for (Alert a : alerts) events.publishEvent(a);
        return alerts;
    }

    /**
     * Fills in the marks between the previous reading (or posting, at zero views) and this one, when one of the two is
     * close to the mark; a straight line between them is then a fair guess. Returns the marks filled.
     */
    static List<Mark> fill(Post p, int views, Instant now) {
        Instant prevAt = p.getViewsCheckedAt() == null ? p.getPostedAt() : p.getViewsCheckedAt();
        int prevViews = p.getViewsCheckedAt() == null || p.getViews() == null ? 0 : p.getViews();
        List<Mark> out = new ArrayList<>();
        for (Mark m : Mark.values()) {
            if (m.of(p) != null) continue;
            Instant at = p.getPostedAt().plus(m.after);
            if (at.isAfter(now) || at.isBefore(prevAt)) continue;
            Duration near = Duration.ofMinutes(Math.max(30, m.after.toMinutes() / 4));
            boolean close = Duration.between(at, now).compareTo(near) <= 0 || Duration.between(prevAt, at).compareTo(near) <= 0;
            if (!close) continue;
            long span = Duration.between(prevAt, now).toSeconds();
            double share = span <= 0 ? 1 : Duration.between(prevAt, at).toSeconds() / (double) span;
            m.set.accept(p, (int) Math.round(prevViews + (views - prevViews) * share));
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------------ pace and report

    /** The median views at each mark over the channel's recent posts, leaving {@code except} out. */
    public Pace pace(Channel ch, Post except) {
        List<Post> recent = posts.findTop60ByChannelIdAndViews1hNotNullOrderByPostedAtDesc(ch.getId());
        return new Pace(median(recent, Mark.H1, except), median(recent, Mark.H6, except), median(recent, Mark.H24, except));
    }

    private static Integer median(List<Post> recent, Mark m, Post except) {
        List<Integer> vals = new ArrayList<>();
        for (Post p : recent) {
            if (except != null && p.getId().equals(except.getId())) continue;
            Integer v = m.of(p);
            if (v != null) vals.add(v);
            if (vals.size() == HISTORY) break;
        }
        if (vals.size() < MIN_HISTORY) return null;
        Collections.sort(vals);
        int n = vals.size();
        return n % 2 == 1 ? vals.get(n / 2) : (vals.get(n / 2 - 1) + vals.get(n / 2)) / 2;
    }

    @Transactional
    public Report report(Channel ch) {
        if (ch.isDemo()) ensureDemo(ch);
        boolean available = ch.isDemo() || (ch.getUsername() != null && !ch.getUsername().isBlank());
        Pace usual = pace(ch, null);
        Instant now = Instant.now();
        List<PostSpeed> out = new ArrayList<>();
        for (Post p : posts.findTop40ByChannelIdOrderByPostedAtDesc(ch.getId())) {
            if (p.getViews() == null && p.getPostedAt().isBefore(now.minus(TRACKED_FOR))) continue;
            out.add(speed(ch, p, usual, now));
            if (out.size() == RECENT) break;
        }
        return new Report(available, usual, out);
    }

    public PostSpeed speed(Channel ch, Post p, Pace usual, Instant now) {
        String preview = p.getBody() == null ? "" : p.getBody().strip().replaceAll("\\s+", " ");
        if (preview.codePointCount(0, preview.length()) > 90) preview = preview.substring(0, preview.offsetByCodePoints(0, 89)) + "…";
        Double ratio = null;
        for (Mark m : Mark.values()) {
            Integer v = m.of(p), u = usual.at(m);
            if (v != null && u != null && u > 0) ratio = Math.round(v * 100.0 / u) / 100.0;
        }
        String status = p.getViews() == null ? "unknown"
                : p.getPostedAt().plus(Mark.H1.after).isAfter(now) ? "early"
                : ratio == null ? "normal" : ratio < LAG ? "lag" : ratio >= FAST ? "fast" : "normal";
        return new PostSpeed(p.getMessageId(), p.getPostedAt(), p.getKind().name(), preview,
                Links.post(ch.getTelegramChatId(), ch.getUsername(), ch.isDemo(), p.getMessageId()),
                p.getViews(), p.getViews1h(), p.getViews6h(), p.getViews24h(), status, ratio);
    }

    public Optional<Post> post(Channel ch, int messageId) { return posts.findByChannelIdAndMessageId(ch.getId(), messageId); }

    // ------------------------------------------------------------------ demo

    /** Gives a sample channel's posts views at each mark the first time it's looked at, the newest one lagging. */
    private void ensureDemo(Channel ch) {
        List<Post> all = posts.findTop40ByChannelIdOrderByPostedAtDesc(ch.getId());
        if (all.isEmpty() || all.stream().anyMatch(p -> p.getViews() != null)) return;
        Instant now = Instant.now();
        Random rng = new Random(ch.getTelegramChatId());
        int subs = ch.getSubscribers() == null ? 5_000 : ch.getSubscribers();
        List<Post> rows = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            Post p = all.get(i);
            double day = subs * (0.22 + p.getReactions() / (double) Math.max(1, subs) * 6) * (0.8 + rng.nextDouble() * 0.4);
            if (i == 0) day *= 0.4; // the newest post is falling behind
            long ageMin = Duration.between(p.getPostedAt(), now).toMinutes();
            int h1 = (int) (day * 0.34), h6 = (int) (day * 0.72), h24 = (int) day;
            if (ageMin >= 60) p.setViews1h(h1);
            if (ageMin >= 360) p.setViews6h(h6);
            if (ageMin >= 1440) p.setViews24h(h24);
            double share = ageMin >= 1440 ? Math.min(1.25, 1 + (ageMin - 1440) / 20_000.0) : ageMin >= 360 ? 0.72 + 0.28 * (ageMin - 360) / 1080.0
                    : ageMin >= 60 ? 0.34 + 0.38 * (ageMin - 60) / 300.0 : 0.34 * ageMin / 60.0;
            p.setViews((int) (day * share));
            p.setViewsCheckedAt(now);
            if (i == 0 && ageMin >= 60) p.setPaceAlert("LAG");
            rows.add(p);
        }
        posts.saveAll(rows);
    }
}
