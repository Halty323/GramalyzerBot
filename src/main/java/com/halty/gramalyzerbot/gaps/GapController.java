package com.halty.gramalyzerbot.gaps;

import com.halty.gramalyzerbot.crm.InsightsService;
import com.halty.gramalyzerbot.tracking.TrackingService;
import com.halty.gramalyzerbot.workspace.WorkspaceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * A CRM channel against the website's watchlist. {@code connected} is false when the bot isn't in the channel, so
 * there are no posts of its own to compare. Authentication is handled for all of {@code /api} by {@code WorkspaceAuth}.
 */
@RestController
public class GapController {

    private final WorkspaceService workspace;
    private final InsightsService insights;
    private final GapService gaps;

    public GapController(WorkspaceService workspace, InsightsService insights, GapService gaps) {
        this.workspace = workspace;
        this.insights = insights;
        this.gaps = gaps;
    }

    public record CompetitorGaps(boolean connected, GapService.Report report) {}

    @GetMapping("/api/channels/{id}/gaps")
    public CompetitorGaps gaps(@PathVariable String id) {
        return insights.analyticsChannel(workspace.channel(id))
                .map(ch -> new CompetitorGaps(true, gaps.report(ch, TrackingService.WEB)))
                .orElse(new CompetitorGaps(false, null));
    }

    @RestControllerAdvice(assignableTypes = GapController.class)
    static class Errors {
        @ExceptionHandler(WorkspaceService.Problem.class)
        ResponseEntity<?> problem(WorkspaceService.Problem e) {
            return ResponseEntity.status(e.status).body(Map.of("error", e.getMessage()));
        }
    }
}
