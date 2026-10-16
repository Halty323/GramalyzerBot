package com.halty.gramalyzerbot;

import com.halty.gramalyzerbot.analytics.*;
import com.halty.gramalyzerbot.post.DemoGenerator;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleTest {

    private static final ZoneId Z = ZoneId.of("Europe/Berlin");

    @Test
    void digestIsDueOncePerWeekInTheRightHour() {
        ZonedDateTime mon9 = ZonedDateTime.of(2026, 10, 12, 9, 5, 0, 0, Z); // a Monday
        Instant now = mon9.toInstant();
        assertTrue(Schedule.digestDue(mon9, DayOfWeek.MONDAY, 9, null, now));
        assertTrue(Schedule.digestDue(mon9, DayOfWeek.MONDAY, 9, now.minus(Duration.ofDays(7)), now));
        assertFalse(Schedule.digestDue(mon9, DayOfWeek.MONDAY, 9, now.minus(Duration.ofMinutes(10)), now));
        assertFalse(Schedule.digestDue(mon9, DayOfWeek.MONDAY, 10, null, now));
        assertFalse(Schedule.digestDue(mon9, DayOfWeek.TUESDAY, 9, null, now));
    }

    private static Report demoReport(Instant now) {
        List<Sample> samples = new ArrayList<>();
        long id = 1;
        for (DemoGenerator.DemoPost p : DemoGenerator.generate(now, Z, 42, 240))
            samples.add(new Sample(id, (int) id++, p.postedAt(), p.kind(), p.text(), p.hasLink(),
                    TextFeatures.hashtags(p.text()), p.reactions(), p.comments(), true, false, null));
        return new AnalysisEngine().analyze(samples, new Options(Z, Metric.ENGAGEMENT, 0, now, Duration.ofHours(24)));
    }

    @Test
    void nudgesFireShortlyBeforeABestSlotOnly() {
        Instant base = Instant.parse("2026-10-15T12:00:00Z");
        Report r = demoReport(base);
        Report.Slot target = null;
        DayOfWeek day = null;
        for (var e : r.bestPerDay().entrySet()) { day = e.getKey(); target = e.getValue().get(0); break; }
        assertNotNull(target);
        // find a date in the future with that weekday
        LocalDate date = LocalDate.of(2026, 10, 19);
        while (date.getDayOfWeek() != day) date = date.plusDays(1);
        ZonedDateTime tenBefore = date.atTime(target.hour(), 0).atZone(Z).minusMinutes(10);
        Instant now = tenBefore.toInstant();
        Instant oldPost = now.minus(Duration.ofHours(20));

        Optional<Report.Slot> due = Schedule.nudgeDue(r, tenBefore, 30, 6, oldPost, null, now);
        assertTrue(due.isPresent());
        assertEquals(target.hour(), due.get().hour());
        // already nudged today
        assertTrue(Schedule.nudgeDue(r, tenBefore, 30, 6, oldPost, now.minus(Duration.ofHours(2)), now).isEmpty());
        // posted recently
        assertTrue(Schedule.nudgeDue(r, tenBefore, 30, 6, now.minus(Duration.ofHours(1)), null, now).isEmpty());
        // too early (45 min before with a 30 min lead)
        ZonedDateTime early = tenBefore.minusMinutes(35);
        assertTrue(Schedule.nudgeDue(r, early, 30, 6, oldPost, null, early.toInstant()).isEmpty());
        // slot already started
        ZonedDateTime late = tenBefore.plusMinutes(15);
        assertTrue(Schedule.nudgeDue(r, late, 30, 6, oldPost, null, late.toInstant()).isEmpty());
    }

    @Test
    void noNudgesWithoutEnoughData() {
        Report empty = new AnalysisEngine().analyze(List.of(), Options.of(Z, Metric.ENGAGEMENT, 90));
        ZonedDateTime t = ZonedDateTime.of(2026, 10, 19, 17, 50, 0, 0, Z);
        assertTrue(Schedule.nudgeDue(empty, t, 30, 6, null, null, t.toInstant()).isEmpty());
    }
}
