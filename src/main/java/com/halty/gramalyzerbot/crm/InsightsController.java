package com.halty.gramalyzerbot.crm;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Read-only analytics and the channel viewer's messages for the website. Authentication is handled for all of {@code /api} by {@code WorkspaceAuth}. */
@RestController
@RequestMapping("/api")
public class InsightsController {

    private final InsightsService insights;
    private final FeedService feed;

    public InsightsController(InsightsService insights, FeedService feed) {
        this.insights = insights;
        this.feed = feed;
    }

    @GetMapping("/channels/{id}/insights")
    public Insights insights(@PathVariable String id) { return insights.forChannel(id); }

    /** The channel's messages for the website's viewer; {@code tz} sets the zone of the posting-hours stats. */
    @GetMapping("/channels/{id}/feed")
    public FeedService.Feed feed(@PathVariable String id, @RequestParam(required = false) String tz) {
        ZoneId zone;
        try {
            zone = tz == null || tz.isBlank() ? ZoneOffset.UTC : ZoneId.of(tz);
        } catch (DateTimeException e) {
            zone = ZoneOffset.UTC;
        }
        return feed.forChannel(id, zone);
    }
}
