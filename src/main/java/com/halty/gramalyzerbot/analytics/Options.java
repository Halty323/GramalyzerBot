package com.halty.gramalyzerbot.analytics;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * @param windowDays look-back window in days; 0 means all time
 * @param maturity   posts younger than this are left out (their reactions are still accumulating)
 */
public record Options(ZoneId zone, Metric metric, int windowDays, Instant now, Duration maturity) {

    public static Options of(ZoneId zone, Metric metric, int windowDays) {
        return new Options(zone, metric, windowDays, Instant.now(), Duration.ofHours(24));
    }
}
