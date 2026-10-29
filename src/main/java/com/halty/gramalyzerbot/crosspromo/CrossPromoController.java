package com.halty.gramalyzerbot.crosspromo;

import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.common.UserFacingException;
import com.halty.gramalyzerbot.crm.InsightsService;
import com.halty.gramalyzerbot.i18n.Lang;
import com.halty.gramalyzerbot.workspace.WorkspaceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cross-promo of a CRM channel for the website: its profile, partners that fit, and swaps. {@code id} is the CRM channel
 * id; authentication is handled for all of {@code /api} by {@code WorkspaceAuth}.
 */
@RestController
@RequestMapping("/api/channels/{id}/cross-promo")
public class CrossPromoController {

    private final WorkspaceService workspace;
    private final InsightsService insights;
    private final SwapService swaps;

    public CrossPromoController(WorkspaceService workspace, InsightsService insights, SwapService swaps) {
        this.workspace = workspace;
        this.insights = insights;
        this.swaps = swaps;
    }

    public record Profile(Topic topic, String language, String pitch, boolean enabled) {}

    /** Everything the cross-promo panel shows; {@code profile} is null until the channel joins. */
    public record CrossPromo(boolean connected, Profile profile, List<Topic> topics, List<String> languages,
                             List<SwapService.Match> matches, List<SwapService.SwapView> swaps) {}

    public record NewSwap(long partner) {}

    @GetMapping
    public CrossPromo get(@PathVariable String id) {
        Optional<Channel> ch = insights.analyticsChannel(workspace.channel(id));
        List<String> languages = Arrays.stream(Lang.values()).map(Lang::code).toList();
        List<Topic> topics = List.of(Topic.values());
        if (ch.isEmpty()) return new CrossPromo(false, null, topics, languages, List.of(), List.of());
        Profile p = swaps.profile(ch.get()).map(s -> new Profile(s.getTopic(), s.getLanguage(), s.getPitch(), s.isEnabled())).orElse(null);
        return new CrossPromo(true, p, topics, languages, swaps.matches(ch.get()), swaps.swaps(ch.get()));
    }

    @PutMapping("/profile")
    public Profile save(@PathVariable String id, @RequestBody Profile body) {
        SwapProfile p = swaps.saveProfile(channel(id), body.topic(), body.language(), body.pitch(), body.enabled());
        return new Profile(p.getTopic(), p.getLanguage(), p.getPitch(), p.isEnabled());
    }

    @PostMapping("/swaps")
    public SwapService.SwapView propose(@PathVariable String id, @RequestBody NewSwap body) {
        Channel ch = channel(id);
        return swaps.swapView(ch, swaps.propose(ch, body.partner()).getId());
    }

    @PostMapping("/swaps/{swapId}/{action}")
    public SwapService.SwapView act(@PathVariable String id, @PathVariable long swapId, @PathVariable String action) {
        Channel ch = channel(id);
        switch (action) {
            case "accept" -> swaps.accept(ch, swapId);
            case "decline" -> swaps.decline(ch, swapId);
            case "cancel" -> swaps.cancel(ch, swapId);
            case "end" -> swaps.end(ch, swapId);
            default -> throw new WorkspaceService.Problem(404, "Unknown action.");
        }
        return swaps.swapView(ch, swapId);
    }

    private Channel channel(String id) {
        return insights.analyticsChannel(workspace.channel(id))
                .orElseThrow(() -> new WorkspaceService.Problem(409, "Add the bot to this channel as an admin first."));
    }

    @RestControllerAdvice(assignableTypes = CrossPromoController.class)
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
