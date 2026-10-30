package com.halty.gramalyzerbot.crm;

import com.halty.gramalyzerbot.analytics.AnalysisEngine;
import com.halty.gramalyzerbot.analytics.Report;
import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.channel.Links;
import com.halty.gramalyzerbot.channel.SubscriberService;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.List;

/**
 * What the analytics engine knows about one channel, as JSON for the CRM website
 * ({@code GET /api/channels/{id}/insights}; the TypeScript twin is {@code shared/types.ts → ChannelInsights}).
 * <p>
 * Weekdays are English upper-case names ("MONDAY"); {@link Heatmap} rows run Monday to Sunday. "Lift" is a
 * multiplier of a typical post (1.0 = typical, 1.5 = half as much again), each post measured against the channel's
 * posts around it; {@code low}–{@code high} is a 90% range. Success is views after 24 hours for public channels, or
 * reactions (plus comments when a discussion group is linked) – see {@code metric}.
 */
public record Insights(
        String channelId,
        /** "ready", "learning" (not enough posts yet; times and content are left empty) or "not_connected". */
        String status,
        Instant generatedAt,
        String timezone,
        String metric,
        String metricFormula,
        int windowDays,
        /** "LOW", "MEDIUM" or "HIGH". */
        String confidence,
        /** Machine-readable caveats: FEW_POSTS, NO_SIGNAL, LOW_TIME_VARIETY. */
        List<String> notes,
        Counts counts,
        /** Whether the post model predicted the channel's newest posts (see {@code Backtest}). */
        Check check,
        /** How many more usable posts are needed before times are shown; 0 once ready. */
        int postsNeeded,
        double avgReactions,
        double avgComments,
        /** The strongest weekday/hour slots, best first. */
        List<Slot> bestTimes,
        /** Rarely used slots that look promising and are worth a test. */
        List<Slot> experiments,
        /** The next upcoming occurrence of each best slot, earliest first – ready to use as a publishing time. */
        List<Suggestion> suggestions,
        List<Day> days,
        List<Hour> hours,
        Heatmap heatmap,
        Content content,
        Cadence cadence,
        List<TopPost> topPosts,
        Audience audience) {

    /** Posts in the analysis window, how many count, and why the rest were left out ({@code ads} of {@code excluded} were sponsored). */
    public record Counts(int total, int eligible, int excluded, int immature, int ads) {}

    /** @param status untested, proven or unproven; @param rho rank correlation on the {@code tested} newest posts */
    public record Check(String status, Double rho, int tested) {}

    /** @param from the first hour of the window the evidence covers, {@code span} hours wide (1, or 4 for young channels) */
    public record Slot(String day, int hour, double lift, int support, int from, int span) {}

    public record Suggestion(Instant at, String day, int hour, double lift) {}

    public record Day(String day, int posts, double lift) {}

    public record Hour(int hour, int posts, double lift) {}

    /** {@code lift[day][hour]} and {@code posts[day][hour]}, day 0 = Monday. */
    public record Heatmap(double[][] lift, int[][] posts) {}

    /**
     * Groups of posts sharing a trait. Keys: kinds = TEXT/PHOTO/VIDEO/…; lengths = NONE/SHORT/MEDIUM/LONG/XLONG;
     * links = LINK/NOLINK; frequency = days with "1"/"2"/"3+" posts, where {@code posts} counts days and lift is the
     * day's total against a one-post day.
     */
    public record Group(String key, int posts, double lift, double avgScore, double low, double high) {}

    public record Content(List<Group> kinds, List<Group> lengths, List<Group> links, List<Group> topics,
                          List<Group> keywords, List<Group> frequency) {}

    public record Cadence(double postsPerWeek, String mostPostedDay, String bestDay, double longestGapDays, Instant lastPost) {}

    public record TopPost(int messageId, Instant postedAt, String kind, String preview, int reactions, int comments,
                          double score, String url) {}

    /** Subscriber trend from the bot's own snapshots (the Bot API only gives the current number). */
    public record Audience(Integer current, Integer change, int days, List<Integer> series) {}

    // ------------------------------------------------------------------ construction

    public static Insights notConnected(String channelId, String timezone) {
        return new Insights(channelId, "not_connected", Instant.now(), timezone, "ENGAGEMENT", "", 0, "LOW", List.of(),
                new Counts(0, 0, 0, 0, 0), new Check("untested", null, 0), AnalysisEngine.MIN_POSTS_FOR_TIMES, 0, 0, List.of(), List.of(), List.of(), List.of(), List.of(),
                new Heatmap(new double[7][24], new int[7][24]),
                new Content(List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                new Cadence(0, null, null, 0, null), List.of(), new Audience(null, null, 0, List.of()));
    }

    public static Insights of(String channelId, Channel ch, Report r, SubscriberService.Growth growth, Instant now) {
        boolean ready = r.hasSignal() && r.eligible() >= AnalysisEngine.MIN_POSTS_FOR_TIMES;
        Audience audience = new Audience(growth.current(), growth.change(), growth.days(), growth.series());
        List<String> notes = r.notes().stream().map(Enum::name).toList();
        Counts counts = new Counts(r.totalInWindow(), r.eligible(), r.excluded(), r.immature(), r.ads());
        Check check = new Check(r.check().status(), r.check().rho(), r.check().tested());
        int needed = Math.max(0, AnalysisEngine.MIN_POSTS_FOR_TIMES - r.eligible());
        String metric = r.metric().name();
        String formula = r.metric().formula();
        ZoneId zone = ch.zone();

        Cadence cadence = new Cadence(round(r.cadence().postsPerWeek()), name(r.cadence().mostPostedDay()),
                name(r.cadence().bestDay()), round(r.cadence().longestGapDays()), r.cadence().lastPost());
        if (!ready) {
            return new Insights(channelId, "learning", now, zone.getId(), metric, formula, ch.getWindowDays(),
                    r.confidence().name(), notes, counts, check, needed, round(r.avgReactions()), round(r.avgComments()),
                    List.of(), List.of(), List.of(), List.of(), List.of(),
                    new Heatmap(new double[7][24], new int[7][24]),
                    new Content(List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                    cadence, List.of(), audience);
        }

        List<Slot> best = r.topSlots().stream().map(Insights::slot).toList();
        List<Suggestion> suggestions = r.topSlots().stream()
                .map(s -> new Suggestion(nextOccurrence(s.day(), s.hour(), zone, now), s.day().name(), s.hour(), round(s.lift())))
                .sorted(Comparator.comparing(Suggestion::at))
                .toList();
        double[][] lift = new double[7][24];
        for (int d = 0; d < 7; d++) for (int h = 0; h < 24; h++) lift[d][h] = round(r.grid()[d][h]);
        int[][] posts = new int[7][];
        for (int d = 0; d < 7; d++) posts[d] = r.gridPosts()[d].clone();

        List<TopPost> top = r.topPosts().stream().map(s -> new TopPost(
                s.sample().messageId(), s.sample().postedAt(), s.sample().kind().name(), preview(s.sample().text()),
                s.sample().reactions(), s.sample().comments(), round(s.score()),
                Links.post(ch.getTelegramChatId(), ch.getUsername(), ch.isDemo(), s.sample().messageId()))).toList();

        return new Insights(channelId, "ready", now, zone.getId(), metric, formula, ch.getWindowDays(),
                r.confidence().name(), notes, counts, check, 0, round(r.avgReactions()), round(r.avgComments()),
                best, r.experiments().stream().map(Insights::slot).toList(), suggestions,
                r.days().stream().map(d -> new Day(d.day().name(), d.posts(), round(d.lift()))).toList(),
                r.hours().stream().map(h -> new Hour(h.hour(), h.posts(), round(h.lift()))).toList(),
                new Heatmap(lift, posts),
                new Content(groups(r.kinds()), groups(r.lengths()), groups(r.links()), groups(r.topics()),
                        groups(r.keywords()), groups(r.frequency())),
                cadence, top, audience);
    }

    /** The next time {@code day} at {@code hour:00} (channel time) that is at least ten minutes away. */
    public static Instant nextOccurrence(DayOfWeek day, int hour, ZoneId zone, Instant now) {
        Instant earliest = now.plus(Duration.ofMinutes(10));
        ZonedDateTime t = now.atZone(zone).with(TemporalAdjusters.nextOrSame(day))
                .withHour(hour).withMinute(0).withSecond(0).withNano(0);
        while (!t.toInstant().isAfter(earliest)) t = t.plusWeeks(1);
        return t.toInstant();
    }

    private static Slot slot(Report.Slot s) { return new Slot(s.day().name(), s.hour(), round(s.lift()), s.support(), s.from(), s.span()); }

    private static List<Group> groups(List<Report.Group> in) {
        return in.stream().map(g -> new Group(g.key(), g.posts(), round(g.lift()), round(g.avgScore()), round(g.low()), round(g.high()))).toList();
    }

    private static String name(DayOfWeek d) { return d == null ? null : d.name(); }

    private static double round(double x) {
        return Double.isFinite(x) ? Math.round(x * 100.0) / 100.0 : 0;
    }

    private static String preview(String text) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (flat.codePointCount(0, flat.length()) <= 140) return flat;
        return flat.substring(0, flat.offsetByCodePoints(0, 139)) + "…";
    }
}
