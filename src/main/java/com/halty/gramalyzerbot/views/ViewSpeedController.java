package com.halty.gramalyzerbot.views;

import com.halty.gramalyzerbot.crm.InsightsService;
import com.halty.gramalyzerbot.workspace.WorkspaceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * How fast a CRM channel's posts gather views, for the website. {@code id} is the CRM channel id; authentication is
 * handled for all of {@code /api} by {@code WorkspaceAuth}.
 */
@RestController
public class ViewSpeedController {

    private final WorkspaceService workspace;
    private final InsightsService insights;
    private final ViewSpeedService speed;

    public ViewSpeedController(WorkspaceService workspace, InsightsService insights, ViewSpeedService speed) {
        this.workspace = workspace;
        this.insights = insights;
        this.speed = speed;
    }

    /** {@link ViewSpeedService.Report} plus whether the bot is in the channel at all. */
    public record ViewSpeed(boolean connected, boolean available, ViewSpeedService.Pace usual, List<ViewSpeedService.PostSpeed> posts) {}

    @GetMapping("/api/channels/{id}/views")
    public ViewSpeed views(@PathVariable String id) {
        return insights.analyticsChannel(workspace.channel(id))
                .map(ch -> {
                    ViewSpeedService.Report r = speed.report(ch);
                    return new ViewSpeed(true, r.available(), r.usual(), r.posts());
                })
                .orElse(new ViewSpeed(false, false, new ViewSpeedService.Pace(null, null, null), List.of()));
    }

    @RestControllerAdvice(assignableTypes = ViewSpeedController.class)
    static class Errors {
        @ExceptionHandler(WorkspaceService.Problem.class)
        ResponseEntity<?> problem(WorkspaceService.Problem e) {
            return ResponseEntity.status(e.status).body(Map.of("error", e.getMessage()));
        }
    }
}
