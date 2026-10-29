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

    @RestControllerAdvice(assignableTypes = SearchCheckController.class)
    static class Errors {
        @ExceptionHandler(WorkspaceService.Problem.class)
        ResponseEntity<?> problem(WorkspaceService.Problem e) {
            return ResponseEntity.status(e.status).body(Map.of("error", e.getMessage()));
        }
    }
}
