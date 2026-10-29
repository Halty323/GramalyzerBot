package com.halty.gramalyzerbot.growth;

import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.common.UserFacingException;
import com.halty.gramalyzerbot.crm.InsightsService;
import com.halty.gramalyzerbot.workspace.WorkspaceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Joins, leaves and promo links of a CRM channel for the website. {@code id} is the CRM channel id; authentication is
 * handled for all of {@code /api} by {@code WorkspaceAuth}.
 */
@RestController
@RequestMapping("/api/channels/{id}")
public class GrowthController {

    private final WorkspaceService workspace;
    private final InsightsService insights;
    private final GrowthService growth;

    public GrowthController(WorkspaceService workspace, InsightsService insights, GrowthService growth) {
        this.workspace = workspace;
        this.insights = insights;
        this.growth = growth;
    }

    /** {@link GrowthService.Report} plus whether the bot is in the channel at all. */
    public record Growth(boolean connected, boolean tracking, Instant since, int days, int joined, int left, int net, int fromLinks,
                         List<GrowthService.Day> daily, List<GrowthService.PostEffect> gainers, List<GrowthService.PostEffect> losers,
                         List<GrowthService.LinkStat> links) {}

    public record NewLink(String name, Double cost, String currency) {}

    @GetMapping("/growth")
    public Growth growth(@PathVariable String id, @RequestParam(defaultValue = "30") int days) {
        int d = Math.max(1, Math.min(days, 365));
        var crm = workspace.channel(id);
        var ch = insights.analyticsChannel(crm);
        if (ch.isEmpty()) return new Growth(false, false, null, d, 0, 0, 0, 0, List.of(), List.of(), List.of(), List.of());
        GrowthService.Report r = growth.report(ch.get(), d);
        return new Growth(true, r.tracking(), r.since(), r.days(), r.joined(), r.left(), r.net(), r.fromLinks(), r.daily(),
                r.gainers(), r.losers(), r.links());
    }

    @PostMapping("/promo-links")
    public GrowthService.LinkStat create(@PathVariable String id, @RequestBody NewLink body) {
        Channel ch = channel(id);
        PromoLink l = growth.create(ch, body.name(), body.cost(), body.currency());
        return stat(ch, l.getId());
    }

    @DeleteMapping("/promo-links/{linkId}")
    public GrowthService.LinkStat revoke(@PathVariable String id, @PathVariable long linkId) {
        Channel ch = channel(id);
        growth.revoke(ch, linkId);
        return stat(ch, linkId);
    }

    private Channel channel(String id) {
        return insights.analyticsChannel(workspace.channel(id))
                .orElseThrow(() -> new WorkspaceService.Problem(409, "Add the bot to this channel as an admin first."));
    }

    private GrowthService.LinkStat stat(Channel ch, long linkId) {
        return growth.linkStats(ch).stream().filter(s -> s.id() == linkId).findFirst()
                .orElseThrow(() -> new WorkspaceService.Problem(404, "Promo link not found."));
    }

    @RestControllerAdvice(assignableTypes = GrowthController.class)
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
