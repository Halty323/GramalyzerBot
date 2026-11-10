package com.halty.gramalyzerbot.goals;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Where a goal stands and how long the posting streak is, from subscriber readings and post times only. The pace is
 * the straight-line change over the last {@value #PACE_DAYS} days (or since the goal was set, if that's shorter);
 * the projection carries that pace on to the deadline, so it is a trend, not a forecast.
 */
public final class GoalMath {

    static final int PACE_DAYS = 14;
    /** Readings this close together say nothing about a pace. */
    static final Duration MIN_SPAN = Duration.ofDays(2);
    static final int WEEKS_SHOWN = 8;

    public enum State { ON_TRACK, BEHIND, REACHED, MISSED, NO_DATA }

    /**
     * @param perDay     subscribers a day lately; null without two readings at least two days apart
     * @param needPerDay subscribers a day still needed to make the deadline
     * @param projected  subscribers on the deadline at the current pace; null without a pace
     * @param eta        the day the target is reached at the current pace; null when the pace isn't positive
     */
    public record Progress(int target, LocalDate deadline, int start, Integer current, int gained, int needed, int percent,
                           int daysLeft, Double perDay, double needPerDay, Integer projected, LocalDate eta, State state,
                           Instant startedAt, Instant reachedAt) {}

    /**
     * @param weeks     full weeks in a row, up to now, with at least {@code target} posts (this week counts once it has)
     * @param best      the longest such run in the history read
     * @param thisWeek  posts so far this week (weeks start on Monday in the channel's timezone)
     * @param daysLeft  days left this week, today included
     * @param recent    posts in each of the last {@value #WEEKS_SHOWN} weeks, oldest first; the last one is this week
     */
    public record Streak(int target, int weeks, int best, int thisWeek, int daysLeft, List<Integer> recent) {
        /** The streak ends this week unless more posts go out. */
        public boolean atRisk() { return weeks > 0 && thisWeek < target; }
        public int missing() { return Math.max(0, target - thisWeek); }
    }

    /** A subscriber count at a moment. */
    public record Reading(Instant at, int count) {}

    private GoalMath() {}

    public static Progress progress(Goal g, Integer current, List<Reading> readings, Instant now, ZoneId zone) {
        LocalDate today = now.atZone(zone).toLocalDate();
        int daysLeft = (int) Math.max(0, ChronoUnit.DAYS.between(today, g.getDeadline()));
        int have = current == null ? g.getStartCount() : current;
        int gained = have - g.getStartCount();
        int needed = Math.max(0, g.getTarget() - have);
        int span = Math.max(1, g.getTarget() - g.getStartCount());
        int percent = (int) Math.max(0, Math.min(100, Math.floor(100.0 * gained / span)));
        Double perDay = pace(readings, current, now, g.getStartedAt());
        double needPerDay = daysLeft == 0 ? needed : (double) needed / daysLeft;
        Integer projected = perDay == null ? null : (int) Math.round(have + perDay * daysLeft);
        LocalDate eta = needed == 0 ? today : perDay == null || perDay <= 0 ? null : today.plusDays((long) Math.ceil(needed / perDay));
        State state;
        if (g.getReachedAt() != null || needed == 0) state = State.REACHED;
        else if (today.isAfter(g.getDeadline())) state = State.MISSED;
        else if (perDay == null) state = State.NO_DATA;
        else state = projected >= g.getTarget() ? State.ON_TRACK : State.BEHIND;
        if (state == State.REACHED) percent = 100;
        return new Progress(g.getTarget(), g.getDeadline(), g.getStartCount(), current, gained, needed, percent, daysLeft,
                perDay == null ? null : Math.round(perDay * 10) / 10.0, Math.round(needPerDay * 10) / 10.0, projected, eta, state,
                g.getStartedAt(), g.getReachedAt());
    }

    /** Subscribers a day over the last {@value #PACE_DAYS} days, from the earliest reading in that window to now. */
    static Double pace(List<Reading> readings, Integer current, Instant now, Instant notBefore) {
        Instant from = now.minus(Duration.ofDays(PACE_DAYS));
        List<Reading> window = new ArrayList<>(readings.stream().filter(r -> !r.at().isBefore(from) && !r.at().isAfter(now)).toList());
        if (current != null) window.add(new Reading(now, current));
        if (window.size() < 2) return null;
        window.sort((a, b) -> a.at().compareTo(b.at()));
        Reading first = window.getFirst(), last = window.getLast();
        Duration d = Duration.between(first.at(), last.at());
        if (d.compareTo(MIN_SPAN) < 0) return null;
        return (last.count() - first.count()) / (d.toMinutes() / 1440.0);
    }

    public static Streak streak(List<Instant> posts, int target, Instant now, ZoneId zone) {
        LocalDate today = now.atZone(zone).toLocalDate();
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        TreeMap<LocalDate, Integer> perWeek = new TreeMap<>();
        for (Instant p : posts) {
            if (p.isAfter(now)) continue;
            LocalDate w = p.atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            perWeek.merge(w, 1, Integer::sum);
        }
        int thisWeek = perWeek.getOrDefault(monday, 0);
        int weeks = thisWeek >= target ? 1 : 0;
        for (LocalDate w = monday.minusWeeks(1); perWeek.getOrDefault(w, 0) >= target; w = w.minusWeeks(1)) weeks++;
        int best = 0, run = 0;
        if (!perWeek.isEmpty()) {
            for (LocalDate w = perWeek.firstKey(); !w.isAfter(monday); w = w.plusWeeks(1)) {
                run = perWeek.getOrDefault(w, 0) >= target ? run + 1 : 0;
                best = Math.max(best, run);
            }
        }
        List<Integer> recent = new ArrayList<>();
        for (int i = WEEKS_SHOWN - 1; i >= 0; i--) recent.add(perWeek.getOrDefault(monday.minusWeeks(i), 0));
        int daysLeft = (int) ChronoUnit.DAYS.between(today, monday.plusWeeks(1));
        return new Streak(target, weeks, Math.max(best, weeks), thisWeek, daysLeft, recent);
    }

    /** The last day of the month that's at least {@code minDays} away: a natural deadline. */
    public static LocalDate monthEnd(LocalDate today, int minDays) {
        LocalDate end = today.with(TemporalAdjusters.lastDayOfMonth());
        while (ChronoUnit.DAYS.between(today, end) < minDays) end = end.plusMonths(1).with(TemporalAdjusters.lastDayOfMonth());
        return end;
    }

    /** A round number at or below x (at least 10): 37 → 35, 230 → 200, 1 340 → 1 000. */
    public static int roundDown(double x) {
        if (x <= 10) return 10;
        double step = Math.pow(10, Math.floor(Math.log10(x))) / 2;
        return (int) Math.max(10, Math.floor(x / step) * step);
    }

    /** A round number at or above x: 37 → 40, 230 → 250, 1 340 → 1 500. */
    public static int round(double x) {
        if (x <= 10) return (int) Math.max(1, Math.ceil(x));
        double step = Math.pow(10, Math.floor(Math.log10(x))) / 2;
        return (int) (Math.ceil(x / step) * step);
    }
}
