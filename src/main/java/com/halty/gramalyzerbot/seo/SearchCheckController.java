package com.halty.gramalyzerbot.seo;

import com.halty.gramalyzerbot.crm.InsightsService;
import com.halty.gramalyzerbot.workspace.Models;
import com.halty.gramalyzerbot.workspace.WorkspaceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** How findable a CRM channel is in Telegram's search. Authentication is handled for all of {@code /api} by {@code WorkspaceAuth}. */
@RestController
public class SearchCheckController {

    private final WorkspaceService workspace;
    private final InsightsService insights;
    private final SearchCheckService search;

    public SearchCheckController(WorkspaceService workspace, InsightsService insights, SearchCheckService search) {
        this.workspace = workspace;
        this.insights = insights;
        this.search = search;
    }

    @GetMapping("/api/channels/{id}/search-check")
    public SearchCheck.Result check(@PathVariable String id) {
        Models.Channel crm = workspace.channel(id);
        return search.check(crm, insights.analyticsChannel(crm));
    }

    public record PositionInput(String word, Integer position) {}

    /** Notes where the channel showed up in Telegram's search for a word; the analytics channel must be connected. */
    @PostMapping("/api/channels/{id}/search-check/positions")
    public java.util.List<SearchCheck.Position> note(@PathVariable String id, @RequestBody PositionInput in) {
        Models.Channel crm = workspace.channel(id);
        var ch = insights.analyticsChannel(crm)
                .orElseThrow(() -> new WorkspaceService.Problem(409, "Connect the bot to this channel to keep search notes."));
        return search.note(ch, in.word(), in.position());
    }

    @RestControllerAdvice(assignableTypes = SearchCheckController.class)
    static class Errors {
        @ExceptionHandler(WorkspaceService.Problem.class)
        ResponseEntity<?> problem(WorkspaceService.Problem e) {
            return ResponseEntity.status(e.status).body(Map.of("error", e.getMessage()));
        }
    }
}
