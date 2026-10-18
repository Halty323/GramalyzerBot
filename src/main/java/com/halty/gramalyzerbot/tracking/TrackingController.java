package com.halty.gramalyzerbot.tracking;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * The website's watchlist of public channels. Authentication is handled for all of {@code /api} by {@code WorkspaceAuth}.
 * Errors are {@code {"error": "…", "code": "not_found"}}; the code is what the website translates.
 * <p>
 * {@code tz} (an IANA zone such as {@code Europe/Kyiv}) sets the zone of the posting-hours histogram; UTC by default.
 */
@RestController
@RequestMapping("/api")
public class TrackingController {

    private final TrackingService tracking;

    public TrackingController(TrackingService tracking) { this.tracking = tracking; }

    public record TrackRequest(String username) {}

    @GetMapping("/tracked")
    public List<TrackingViews.Summary> list(@RequestParam(required = false) String tz) {
        ZoneId zone = zone(tz);
        return tracking.watched(TrackingService.WEB).stream()
                .map(c -> TrackingViews.summary(c, tracking.stats(c, zone), true)).toList();
    }

    @PostMapping("/tracked")
    public ResponseEntity<TrackingViews.Detail> track(@RequestBody TrackRequest body, @RequestParam(required = false) String tz) {
        TrackedChannel c = tracking.track(TrackingService.WEB, body == null ? null : body.username());
        return ResponseEntity.status(201).body(detail(c, zone(tz)));
    }

    /** Any public channel, tracked or not: what the viewer shows when someone types a username. */
    @GetMapping("/tracked/{username}")
    public TrackingViews.Detail get(@PathVariable String username, @RequestParam(required = false) String tz) {
        return detail(tracking.lookup(username), zone(tz));
    }

    @PostMapping("/tracked/{username}/refresh")
    public TrackingViews.Detail refresh(@PathVariable String username, @RequestParam(required = false) String tz) {
        TrackedChannel c = tracking.find(username).orElseGet(() -> tracking.lookup(username));
        return detail(tracking.refresh(c, true), zone(tz));
    }

    @DeleteMapping("/tracked/{username}")
    public ResponseEntity<Void> untrack(@PathVariable String username) {
        tracking.untrack(TrackingService.WEB, username);
        return ResponseEntity.noContent().build();
    }

    private TrackingViews.Detail detail(TrackedChannel c, ZoneId zone) {
        return TrackingViews.detail(c, tracking.stats(c, zone), tracking.posts(c), tracking.watching(TrackingService.WEB, c));
    }

    static ZoneId zone(String tz) {
        if (tz == null || tz.isBlank()) return ZoneOffset.UTC;
        try {
            return ZoneId.of(tz);
        } catch (DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }

    @RestControllerAdvice
    static class Errors {
        @ExceptionHandler(TrackingException.class)
        ResponseEntity<?> tracking(TrackingException e) {
            return ResponseEntity.status(e.code().status).body(Map.of("error", e.getMessage(), "code", e.code().key()));
        }
    }
}
