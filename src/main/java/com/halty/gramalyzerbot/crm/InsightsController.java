package com.halty.gramalyzerbot.crm;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only analytics for the website. Authentication is handled for all of {@code /api} by {@code WorkspaceAuth}. */
@RestController
@RequestMapping("/api")
public class InsightsController {

    private final InsightsService insights;

    public InsightsController(InsightsService insights) { this.insights = insights; }

    @GetMapping("/channels/{id}/insights")
    public Insights insights(@PathVariable String id) { return insights.forChannel(id); }
}
