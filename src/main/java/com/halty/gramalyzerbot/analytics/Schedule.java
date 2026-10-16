package com.halty.gramalyzerbot.analytics;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/** Pure timing rules for the weekly digest and the "good time to post" nudges. */
public final class Schedule {

    private Schedule() {}

    /** The digest is due during the configured hour, at most once per ~week. */
    public static boolean digestDue(ZonedDateTime local, DayOfWeek day, int hour, Instant lastDigest, Instant now) {
        if (local.getDayOfWeek() != day || local.getHour() != hour) return false;
        return lastDigest == null || lastDigest.isBefore(now.minus(Duration.ofDays(6)));
    }

    /**
     * A nudge is due when one of today's best slots starts within {@code leadMinutes}, nothing was posted for
     * {@code gapHours} and no nudge was already sent today.
     */
    public static Optional<Report.Slot> nudgeDue(Report r, ZonedDateTime local, int leadMinutes, int gapHours,
                                                  Instant lastPost, Instant lastNudge, Instant now) {
        if (!r.hasSignal() || r.eligible() < AnalysisEngine.MIN_POSTS_FOR_TIMES) return Optional.empty();
        if (lastPost != null && lastPost.isAfter(now.minus(Duration.ofHours(gapHours)))) return Optional.empty();
        if (lastNudge != null && lastNudge.atZone(local.getZone()).toLocalDate().equals(local.toLocalDate())) return Optional.empty();
        List<Report.Slot> today = r.bestPerDay().getOrDefault(local.getDayOfWeek(), List.of());
        for (Report.Slot s : today) {
            ZonedDateTime start = local.toLocalDate().atTime(s.hour(), 0).atZone(local.getZone());
            long minutes = Duration.between(local, start).toMinutes();
            if (minutes > 0 && minutes <= leadMinutes) return Optional.of(s);
        }
        return Optional.empty();
    }
}
