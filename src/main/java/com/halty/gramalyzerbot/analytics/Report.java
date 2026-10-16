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
        List<Group> frequency,
        Cadence cadence,
        List<Scored> topPosts) {

    public enum Confidence { LOW, MEDIUM, HIGH }

    public enum Note { FEW_POSTS, NO_SIGNAL, LOW_TIME_VARIETY }

    /** A group of posts sharing a trait. {@code lift} is a shrunk "× typical engagement" multiplier. */
    public record Group(String key, int posts, double lift, double avgScore) {}

    /** A weekday/hour slot. {@code support} = posts on that weekday within ±1 hour. */
    public record Slot(DayOfWeek day, int hour, double lift, int support) {}

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
                new Cadence(0, null, null, 0, null), List.of());
    }
}
