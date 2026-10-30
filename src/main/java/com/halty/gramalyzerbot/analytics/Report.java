package com.halty.gramalyzerbot.analytics;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Everything the bot can say about a channel, as plain data. */
public record Report(
        int totalInWindow,
        int eligible,
        int excluded,
        int immature,
        Confidence confidence,
        List<Note> notes,
        double avgReactions,
        double avgComments,
        // --- when ---
        List<Slot> topSlots,
        List<Slot> experiments,
        Map<DayOfWeek, List<Slot>> bestPerDay,
        List<DayStat> days,
        List<HourStat> hours,
        double[][] grid,
        int[][] gridPosts,
        // --- what ---
        List<Group> kinds,
        List<Group> lengths,
        List<Group> links,
        List<Group> topics,
        List<Group> keywords,
        /** Days grouped by how many posts went out: key "1", "2" or "3+", {@code posts} = days, lift = the day's total against a one-post day. */
        List<Group> frequency,
        Cadence cadence,
        List<Scored> topPosts,
        /** The metric the scores are in: {@link Metric#AUTO} already resolved to views or engagement. */
        Metric metric,
        /** Sponsored posts left out (also counted in {@code excluded}). */
        int ads,
        /** Every trait fitted together; null when there was nothing to fit. */
        PostModel model,
        /** How well {@code model} predicted the channel's newest posts. */
        Backtest.Check check) {

    public enum Confidence { LOW, MEDIUM, HIGH }

    public enum Note { FEW_POSTS, NO_SIGNAL, LOW_TIME_VARIETY }

    /**
     * A group of posts sharing a trait. {@code lift} is a shrunk "× a typical post" multiplier, each post measured
     * against its neighbours ({@link Baseline}); {@code low}–{@code high} is its 90% range.
     */
    public record Group(String key, int posts, double lift, double avgScore, double low, double high) {
        public Group(String key, int posts, double lift, double avgScore) { this(key, posts, lift, avgScore, lift, lift); }

        /** The range lies wholly above or below 1, so the difference is more than noise. */
        public boolean clear() { return low > 1 || high < 1; }
    }

    /**
     * A weekday slot. {@code hour} is when to post; {@code span} is how wide the evidence is: 1 for a single hour, or
     * {@value PostModel#BLOCK_HOURS} for a block starting at {@code from}, used until a channel has enough posts to tell
     * hours apart. {@code support} = posts in that window (±1 hour for single hours).
     */
    public record Slot(DayOfWeek day, int hour, double lift, int support, int from, int span) {
        public Slot(DayOfWeek day, int hour, double lift, int support) { this(day, hour, lift, support, hour, 1); }
    }

    public record DayStat(DayOfWeek day, int posts, double lift) {}

    public record HourStat(int hour, int posts, double lift) {}

    public record Cadence(double postsPerWeek, DayOfWeek mostPostedDay, DayOfWeek bestDay, double longestGapDays,
                          Instant lastPost) {}

    public record Scored(Sample sample, double score) {}

    public boolean hasSignal() { return !notes.contains(Note.NO_SIGNAL) && eligible > 0; }

    public static Report empty(int totalInWindow, int excluded, int immature, List<Note> notes) {
        return new Report(totalInWindow, 0, excluded, immature, Confidence.LOW, notes, 0, 0,
                List.of(), List.of(), Map.of(), List.of(), List.of(), new double[7][24], new int[7][24],
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                new Cadence(0, null, null, 0, null), List.of(), Metric.ENGAGEMENT, 0, null, Backtest.Check.untested());
    }

    public static Report empty(int totalInWindow, int excluded, int immature, List<Note> notes, Metric metric, int ads) {
        Report r = empty(totalInWindow, excluded, immature, notes);
        return new Report(r.totalInWindow, r.eligible, r.excluded, r.immature, r.confidence, r.notes, 0, 0, r.topSlots, r.experiments,
                r.bestPerDay, r.days, r.hours, r.grid, r.gridPosts, r.kinds, r.lengths, r.links, r.topics, r.keywords, r.frequency,
                r.cadence, r.topPosts, metric, ads, null, Backtest.Check.untested());
    }
}
