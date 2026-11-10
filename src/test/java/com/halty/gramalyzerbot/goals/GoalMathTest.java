package com.halty.gramalyzerbot.goals;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Pace, projection and streak arithmetic. */
class GoalMathTest {

    private static final ZoneId UTC = ZoneOffset.UTC;
    // a Wednesday
    private static final Instant NOW = LocalDate.of(2026, 11, 11).atTime(12, 0).toInstant(ZoneOffset.UTC);

    private static Goal goal(int start, int target, LocalDate deadline, int daysAgo) {
        Goal g = new Goal();
        g.setStartCount(start);
        g.setTarget(target);
        g.setDeadline(deadline);
        g.setStartedAt(NOW.minus(Duration.ofDays(daysAgo)));
        return g;
    }

    /** One reading a day for the last {@code days} days, growing by {@code perDay}. */
    private static List<GoalMath.Reading> daily(int days, int endCount, int perDay) {
        List<GoalMath.Reading> out = new ArrayList<>();
        for (int i = days; i >= 1; i--) out.add(new GoalMath.Reading(NOW.minus(Duration.ofDays(i)), endCount - i * perDay));
        return out;
    }

    @Test
    void aSteadyPaceThatGetsThereInTimeIsOnTrack() {
        GoalMath.Progress p = GoalMath.progress(goal(1000, 1500, LocalDate.of(2026, 12, 31), 10), 1200, daily(14, 1200, 10), NOW, UTC);
        assertEquals(GoalMath.State.ON_TRACK, p.state());
        assertEquals(40, p.percent());
        assertEquals(200, p.gained());
        assertEquals(300, p.needed());
        assertEquals(50, p.daysLeft());
        assertEquals(10.0, p.perDay(), 0.01);
        assertEquals(6.0, p.needPerDay(), 0.01);
        assertEquals(1700, p.projected());
        assertEquals(LocalDate.of(2026, 12, 11), p.eta());
    }

    @Test
    void aSlowPaceIsBehindAndALosingOneHasNoEta() {
        GoalMath.Progress slow = GoalMath.progress(goal(1000, 2000, LocalDate.of(2026, 11, 30), 10), 1100, daily(14, 1100, 2), NOW, UTC);
        assertEquals(GoalMath.State.BEHIND, slow.state());
        assertEquals(1138, slow.projected());
        GoalMath.Progress losing = GoalMath.progress(goal(1000, 2000, LocalDate.of(2026, 11, 30), 10), 990, daily(14, 990, -3), NOW, UTC);
        assertEquals(GoalMath.State.BEHIND, losing.state());
        assertNull(losing.eta());
        assertEquals(0, losing.percent(), "losing subscribers is no progress, not negative progress");
    }

    @Test
    void reachedMissedAndTooFewReadings() {
        assertEquals(GoalMath.State.REACHED, GoalMath.progress(goal(1000, 1100, LocalDate.of(2026, 12, 1), 5), 1105, List.of(), NOW, UTC).state());
        GoalMath.Progress missed = GoalMath.progress(goal(1000, 1500, LocalDate.of(2026, 11, 1), 40), 1200, daily(14, 1200, 5), NOW, UTC);
        assertEquals(GoalMath.State.MISSED, missed.state());
        assertEquals(300, missed.needed());
        List<GoalMath.Reading> oneHourAgo = List.of(new GoalMath.Reading(NOW.minus(Duration.ofHours(1)), 1000));
        assertEquals(GoalMath.State.NO_DATA, GoalMath.progress(goal(1000, 1500, LocalDate.of(2026, 12, 1), 0), 1001, oneHourAgo, NOW, UTC).state());
    }

    private static Instant at(LocalDate d) { return d.atTime(18, 0).toInstant(ZoneOffset.UTC); }

    @Test
    void theStreakCountsFullWeeksAndThisWeekOnceItsDone() {
        LocalDate monday = LocalDate.of(2026, 11, 9);
        List<Instant> posts = new ArrayList<>();
        // 3 posts in each of the last four weeks, a gap the week before, 3 posts two weeks before that
        for (int w = 1; w <= 4; w++) for (int d = 0; d < 3; d++) posts.add(at(monday.minusWeeks(w).plusDays(d)));
        for (int d = 0; d < 3; d++) posts.add(at(monday.minusWeeks(6).plusDays(d)));
        posts.add(at(monday)); // one so far this week

        GoalMath.Streak s = GoalMath.streak(posts, 3, NOW, UTC);
        assertEquals(4, s.weeks());
        assertEquals(4, s.best());
        assertEquals(1, s.thisWeek());
        assertEquals(5, s.daysLeft(), "Wednesday to Sunday");
        assertTrue(s.atRisk());
        assertEquals(2, s.missing());
        assertEquals(List.of(0, 3, 0, 3, 3, 3, 3, 1), s.recent());

        posts.add(at(monday.plusDays(1)));
        posts.add(at(monday.plusDays(2)).minus(Duration.ofHours(8)));
        GoalMath.Streak done = GoalMath.streak(posts, 3, NOW, UTC);
        assertEquals(5, done.weeks());
        assertFalse(done.atRisk());

        GoalMath.Streak none = GoalMath.streak(List.of(at(monday.minusWeeks(3))), 3, NOW, UTC);
        assertEquals(0, none.weeks());
        assertFalse(none.atRisk(), "nothing to lose");
    }

    @Test
    void niceNumbersAndMonthEnds() {
        assertEquals(40, GoalMath.round(37));
        assertEquals(250, GoalMath.round(230));
        assertEquals(1500, GoalMath.round(1340));
        assertEquals(7, GoalMath.round(6.2));
        assertEquals(35, GoalMath.roundDown(37));
        assertEquals(200, GoalMath.roundDown(230));
        assertEquals(1000, GoalMath.roundDown(1340));
        assertEquals(LocalDate.of(2026, 11, 30), GoalMath.monthEnd(LocalDate.of(2026, 11, 10), 14));
        assertEquals(LocalDate.of(2026, 12, 31), GoalMath.monthEnd(LocalDate.of(2026, 11, 20), 14));
    }

    @Test
    void goalTextIsRead() {
        LocalDate today = LocalDate.of(2026, 11, 10);
        assertEquals(new GoalService.Parsed(1500, LocalDate.of(2026, 12, 31)), GoalService.parse("+500", 1000, today).orElseThrow());
        assertEquals(new GoalService.Parsed(5000, LocalDate.of(2026, 12, 31)), GoalService.parse("5000", 1000, today).orElseThrow());
        assertEquals(new GoalService.Parsed(2500, LocalDate.of(2027, 3, 1)), GoalService.parse("+1.5k 1.3.2027", 1000, today).orElseThrow());
        assertEquals(new GoalService.Parsed(5000, LocalDate.of(2026, 12, 31)), GoalService.parse("5,000 2026-12-31", 1000, today).orElseThrow());
        assertEquals(new GoalService.Parsed(3000, LocalDate.of(2027, 1, 15)), GoalService.parse("3к до 15.01", 1000, today).orElseThrow());
        assertEquals(LocalDate.of(2027, 11, 1), GoalService.parse("+10 1.11", 1000, today).orElseThrow().deadline(), "a day-month already past is next year");
        assertTrue(GoalService.parse("lots", 1000, today).isEmpty());
        assertTrue(GoalService.parse("+500 31.02.2027", 1000, today).isEmpty());
    }
}
