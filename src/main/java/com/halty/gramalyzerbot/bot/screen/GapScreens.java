package com.halty.gramalyzerbot.bot.screen;

import com.halty.gramalyzerbot.bot.ui.*;
import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.gaps.GapService;
import com.halty.gramalyzerbot.gaps.GapService.Gap;
import com.halty.gramalyzerbot.i18n.Tr;
import com.halty.gramalyzerbot.tracking.TrackingService;
import com.halty.gramalyzerbot.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/** Where the channels on someone's watchlist win and their channel doesn't, and where it's ahead. */
@Component
@RequiredArgsConstructor
public class GapScreens {

    private final GapService gaps;

    public View list(Channel ch, User u) {
        long id = ch.getId();
        GapService.Report r = gaps.report(ch, TrackingService.owner(u));
        View v = new View().line(Tr.t("gap.title")).line("<i>" + Fmt.esc(ch.getTitle()) + "</i>").blank();
        switch (r.status()) {
            case "no_competitors" -> v.line(Tr.t("gap.none"));
            case "learning" -> v.line(Tr.t("gap.learning", r.theirPosts(), r.yourPosts()));
            default -> {
                String names = r.rivals().stream().map(x -> "@" + Fmt.esc(x.username())).collect(Collectors.joining(", "));
                v.line(Tr.t("gap.compared", names, r.theirPosts(), r.yourPosts()));
                if (r.measure().equals("ENGAGEMENT")) v.line("<i>" + Tr.t("gap.by_engagement") + "</i>");
                v.blank().line(Tr.t("gap.they_win"));
                if (r.gaps().isEmpty()) v.line(Tr.t("gap.no_gaps"));
                for (Gap g : r.gaps()) {
                    v.line("• <b>" + label(g) + "</b> — " + Tr.t("gap.their", Fmt.lift(g.theirLift()), Math.round(g.theirShare() * 100)));
                    v.line("   " + (g.missing() ? g.yourPosts() == 0 ? Tr.t("gap.you_never") : Tr.t("gap.you_rarely", Math.round(g.yourShare() * 100))
                            : Tr.t("gap.you_weaker", Fmt.lift(g.yourLift() == null ? 0 : g.yourLift()))));
                    if (g.example() != null && !g.example().preview().isBlank()) {
                        String text = "«" + Fmt.esc(Fmt.trunc(g.example().preview(), 60)) + "»";
                        if (g.example().url() != null) text = "<a href=\"" + g.example().url() + "\">" + text + "</a>";
                        v.line("   " + Tr.t("gap.example", text, "@" + Fmt.esc(g.example().channel())));
                    }
                }
                if (!r.edges().isEmpty()) {
                    v.blank().line(Tr.t("gap.you_win"));
                    for (Gap g : r.edges()) v.line("• <b>" + label(g) + "</b> — " + Tr.t("gap.yours", Fmt.lift(g.yourLift())));
                }
                v.blank().line("<i>" + Tr.t("gap.legend") + "</i>");
            }
        }
        if (!r.status().equals("ready")) v.row(Btn.cb(Tr.t("btn.watchlist"), "wl:list"));
        return v.row(Btn.cb(Tr.t("btn.refresh"), Cb.of("gap:list", id)), Btn.cb(Tr.t("btn.dashboard"), Cb.of("nav:home", id)));
    }

    static String label(Gap g) {
        return switch (g.dimension()) {
            case FORMAT -> Labels.kind(g.key());
            case HOURS -> {
                int h = Integer.parseInt(g.key());
                yield "🕓 " + Fmt.hour(h) + "–" + Fmt.hour((h + 4) % 24);
            }
            case LENGTH -> "📏 " + Labels.length(g.key());
            case TOPIC -> g.key().startsWith("#") ? Fmt.esc(g.key()) : "💬 «" + Fmt.esc(g.key()) + "»";
        };
    }
}
