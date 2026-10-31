package com.halty.gramalyzerbot.refresh;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * The website and Studio call {@code POST /api/refresh} when they open and when they close, so every channel is current
 * whenever someone looks. {@code GET} tells how the last pass went, so an app can reload once a pass has finished.
 */
@RestController
@RequestMapping("/api/refresh")
@RequiredArgsConstructor
public class RefreshController {

    private final ChannelRefresh refresh;

    @GetMapping
    public ChannelRefresh.Status status() {
        return refresh.status();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ChannelRefresh.Status request(@RequestParam(defaultValue = "app") String reason) {
        String r = reason.replaceAll("[^a-z0-9-]", "");
        return refresh.request(r.isEmpty() ? "app" : r.substring(0, Math.min(r.length(), 24)));
    }
}
