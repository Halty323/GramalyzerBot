package com.halty.gramalyzerbot.tracking;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Refreshes every watched channel regularly, so its posts' views keep up and its subscriber history gets one point a
 * day. Runs with or without the bot: the public preview needs no token.
 */
@Component
@ConditionalOnProperty(name = "gramalyzer.tracking.refresh-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class TrackingJobs {

    private final TrackingService tracking;

    @Scheduled(fixedDelayString = "${gramalyzer.tracking.refresh-every:PT2H}", initialDelayString = "PT3M")
    public void refreshWatched() {
        for (TrackedChannel c : tracking.watchedAnywhere()) {
            if (c.isDemo()) continue;
            try {
                tracking.refresh(c, false);
                pause();
            } catch (RuntimeException e) {
                log.warn("Refreshing @{} failed", c.getUsername(), e);
            }
        }
    }

    @Scheduled(fixedDelayString = "PT12H", initialDelayString = "PT10M")
    public void forgetUnwatched() {
        int n = tracking.forgetUnwatched(Duration.ofDays(3));
        if (n > 0) log.info("Forgot {} public channels nobody tracks", n);
    }

    /** One page a second or so: t.me is a public website, not an API. */
    private static void pause() {
        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
