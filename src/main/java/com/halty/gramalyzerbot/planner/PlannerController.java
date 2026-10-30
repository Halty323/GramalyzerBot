package com.halty.gramalyzerbot.planner;

import com.halty.gramalyzerbot.analytics.TextFeatures;
import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.crm.InsightsService;
import com.halty.gramalyzerbot.workspace.Models;
import com.halty.gramalyzerbot.workspace.WorkspaceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The queue on the channel's best times, for the website: a draft's score, the next free good slots, drafts spread
 * over them, and evergreen reposts. {@code id} is the CRM channel id; authentication is handled for all of
 * {@code /api} by {@code WorkspaceAuth}. Without the bot in the channel there is no analysis, so {@code connected} is false.
 */
@RestController
@RequestMapping("/api/channels/{id}")
public class PlannerController {

    private final WorkspaceService workspace;
    private final InsightsService insights;
    private final PlannerService planner;

    public PlannerController(WorkspaceService workspace, InsightsService insights, PlannerService planner) {
        this.workspace = workspace;
        this.insights = insights;
        this.planner = planner;
    }

    /** @param media the kinds of the attached files (photo, video, animation); @param scheduledAt null when not chosen */
    public record DraftInput(String body, List<String> media, int buttons, Instant scheduledAt, String postId) {}

    public record Scored(boolean connected, DraftScore.Result score, List<PlannerService.Slot> slots) {}

    public record FillInput(List<String> postIds, boolean apply) {}

    public record EvergreenList(boolean connected, List<PlannerService.Evergreen> posts) {}

    @PostMapping("/draft-score")
    public Scored score(@PathVariable String id, @RequestBody DraftInput in) {
        Models.Channel crm = workspace.channel(id);
        return insights.analyticsChannel(crm).map(ch -> {
            String body = in.body() == null ? "" : in.body();
            DraftScore.Draft d = new DraftScore.Draft(body, PlannerService.kindOf(in.media()), in.buttons() > 0 || TextFeatures.hasLink(body), in.scheduledAt());
            return new Scored(true, planner.score(ch, d), planner.free(ch, id, in.postId(), 3));
        }).orElse(new Scored(false, null, List.of()));
    }

    @GetMapping("/best-slots")
    public List<PlannerService.Slot> slots(@PathVariable String id, @RequestParam(defaultValue = "5") int count, @RequestParam(required = false) String except) {
        return insights.analyticsChannel(workspace.channel(id)).map(ch -> planner.free(ch, id, except, Math.max(1, Math.min(count, 20)))).orElse(List.of());
    }

    @PostMapping("/fill-slots")
    public List<PlannerService.Assignment> fill(@PathVariable String id, @RequestBody FillInput in) {
        Channel ch = analytics(id);
        return planner.fill(ch, id, in.postIds(), in.apply());
    }

    @GetMapping("/evergreen")
    public EvergreenList evergreen(@PathVariable String id) {
        return insights.analyticsChannel(workspace.channel(id)).map(ch -> new EvergreenList(true, planner.evergreen(ch, id)))
                .orElse(new EvergreenList(false, List.of()));
    }

    @PostMapping("/evergreen/{messageId}")
    public ResponseEntity<Models.Post> repost(@PathVariable String id, @PathVariable int messageId) {
        return ResponseEntity.status(201).body(planner.repost(analytics(id), id, messageId));
    }

    private Channel analytics(String id) {
        return insights.analyticsChannel(workspace.channel(id))
                .orElseThrow(() -> new WorkspaceService.Problem(409, "Add the bot to this channel first, so its best times are known."));
    }

    @RestControllerAdvice(assignableTypes = PlannerController.class)
    static class Errors {
        @ExceptionHandler(WorkspaceService.Problem.class)
        ResponseEntity<?> problem(WorkspaceService.Problem e) {
            return ResponseEntity.status(e.status).body(Map.of("error", e.getMessage()));
        }
    }
}
