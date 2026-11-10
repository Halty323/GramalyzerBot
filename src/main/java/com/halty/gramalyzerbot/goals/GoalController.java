package com.halty.gramalyzerbot.goals;

import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.common.UserFacingException;
import com.halty.gramalyzerbot.crm.InsightsService;
import com.halty.gramalyzerbot.workspace.WorkspaceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/**
 * A CRM channel's subscriber goal and posting streak for the website. {@code id} is the CRM channel id;
 * authentication is handled for all of {@code /api} by {@code WorkspaceAuth}.
 */
@RestController
@RequestMapping("/api/channels/{id}")
public class GoalController {

    private final WorkspaceService workspace;
    private final InsightsService insights;
    private final GoalService goals;

    public GoalController(WorkspaceService workspace, InsightsService insights, GoalService goals) {
        this.workspace = workspace;
        this.insights = insights;
        this.goals = goals;
    }

    /** {@link GoalService.Status} plus whether the bot is in the channel at all. */
    public record Goals(boolean connected, GoalService.Status status) {}

    public record GoalInput(int target, LocalDate deadline) {}

    public record StreakInput(int postsPerWeek) {}

    @GetMapping("/goal")
    public Goals get(@PathVariable String id) {
        return insights.analyticsChannel(workspace.channel(id)).map(ch -> new Goals(true, goals.status(ch))).orElse(new Goals(false, null));
    }

    @PutMapping("/goal")
    public Goals set(@PathVariable String id, @RequestBody GoalInput in) {
        if (in.deadline() == null) throw new UserFacingException("goal.err_date");
        Channel ch = channel(id);
        goals.set(ch, in.target(), in.deadline(), null);
        return new Goals(true, goals.status(ch));
    }

    @DeleteMapping("/goal")
    public Goals clear(@PathVariable String id) {
        Channel ch = channel(id);
        goals.clear(ch);
        return new Goals(true, goals.status(ch));
    }

    @PutMapping("/streak")
    public Goals streak(@PathVariable String id, @RequestBody StreakInput in) {
        Channel ch = goals.setStreakTarget(channel(id), in.postsPerWeek());
        return new Goals(true, goals.status(ch));
    }

    private Channel channel(String id) {
        return insights.analyticsChannel(workspace.channel(id))
                .orElseThrow(() -> new WorkspaceService.Problem(409, "Add the bot to this channel as an admin first."));
    }

    @RestControllerAdvice(assignableTypes = GoalController.class)
    static class Errors {
        @ExceptionHandler(UserFacingException.class)
        ResponseEntity<?> refused(UserFacingException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage().replaceAll("<[^>]+>", ""), "code", e.key()));
        }

        @ExceptionHandler(WorkspaceService.Problem.class)
        ResponseEntity<?> problem(WorkspaceService.Problem e) {
            return ResponseEntity.status(e.status).body(Map.of("error", e.getMessage()));
        }
    }
}
