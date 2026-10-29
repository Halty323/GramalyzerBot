package com.halty.gramalyzerbot.bot.screen;

import com.halty.gramalyzerbot.analytics.Report;
import com.halty.gramalyzerbot.bot.ui.*;
import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.channel.Permission;
import com.halty.gramalyzerbot.channel.Role;
import com.halty.gramalyzerbot.crm.Insights;
import com.halty.gramalyzerbot.i18n.Tr;
import com.halty.gramalyzerbot.post.AnalysisService;
import com.halty.gramalyzerbot.post.Post;
import com.halty.gramalyzerbot.views.ViewSpeedService;
import com.halty.gramalyzerbot.views.ViewSpeedService.Alert;
import com.halty.gramalyzerbot.views.ViewSpeedService.Pace;
import com.halty.gramalyzerbot.views.ViewSpeedService.PostSpeed;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;

/** How fast posts gather views, and the alert about a post that lags behind or flies ahead of the usual pace. */
@Component
@RequiredArgsConstructor
public class ViewSpeedScreens {

    private static final int SHOWN = 8;

    private final ViewSpeedService speed;
    private final AnalysisService analysis;

    public View list(Channel ch, Role role) {
        long id = ch.getId();
        ViewSpeedService.Report r = speed.report(ch);
        View v = new View().line(Tr.t("speed.title")).line("<i>" + Fmt.esc(ch.getTitle()) + "</i>").blank();
        if (!r.available()) {
            v.line(Tr.t("speed.private"));
            return v.row(Btn.cb(Tr.t("btn.dashboard"), Cb.of("nav:home", id)));
        }
        Pace u = r.usual();
        if (u.h1() == null && u.h6() == null && u.h24() == null) v.line(Tr.t("speed.learning"));
        else v.line(Tr.t("speed.usual", num(u.h1()), num(u.h6()), num(u.h24())));
        if (r.posts().isEmpty()) {
            v.blank().line(Tr.t("speed.empty"));
        } else {
            v.blank();
            for (PostSpeed p : r.posts().subList(0, Math.min(SHOWN, r.posts().size()))) {
                String what = Fmt.esc(p.preview().isBlank() ? Labels.kind(p.kind()) : Fmt.trunc(p.preview(), 45));
                if (p.url() != null) what = "<a href=\"" + p.url() + "\">" + what + "</a>";
                v.line(icon(p.status()) + " " + what);
                v.line("     " + Tr.t("speed.marks", num(p.h1()), num(p.h6()), num(p.h24()))
                        + (p.ratio() != null ? " · " + Tr.t("speed.ratio", Math.round(p.ratio() * 100)) : "")
                        + " · " + InsightScreens.when(p.postedAt().atZone(ch.zone())));
            }
            v.blank().line("<i>" + Tr.t("speed.legend") + "</i>");
        }
        return v.row(Btn.cb(Tr.t("btn.refresh"), Cb.of("vs:list", id)), Btn.cb(Tr.t("btn.dashboard"), Cb.of("nav:home", id)));
    }

    private static String icon(String status) {
        return switch (status) { case "lag" -> "🐢"; case "fast" -> "🚀"; case "early" -> "⏳"; case "unknown" -> "▫️"; default -> "•"; };
    }

    private static String num(Integer n) { return n == null ? "–" : Fmt.compact(n); }

    /** Sent to the team when a new post lags behind or flies ahead of the channel's usual pace. */
    public View alert(Alert a, Role role) {
        Channel ch = a.channel();
        Post p = a.post();
        String what = p.getBody() == null || p.getBody().isBlank() ? Labels.kind(p.getKind().name()) : Fmt.trunc(p.getBody().strip(), 70);
        String hours = a.mark() == ViewSpeedService.Mark.H1 ? Tr.t("speed.after_1h") : Tr.t("speed.after_6h");
        View v = View.of(Tr.t(a.lagging() ? "speed.lag_title" : "speed.fast_title", Fmt.esc(ch.getTitle())))
                .line("«" + Fmt.esc(what) + "»")
                .line(Tr.t("speed.counts", Fmt.num(a.views()), hours, Fmt.num(a.usual()), Math.round(a.ratio() * 100)))
                .blank();
        if (a.lagging()) {
            v.line(Tr.t("speed.lag_tips"));
            nextSlot(ch).ifPresent(at -> v.line(Tr.t("speed.next_slot", InsightScreens.when(at.atZone(ch.zone())))));
        } else {
            v.line(Tr.t("speed.fast_tips"));
        }
        long id = ch.getId();
        if (role.can(Permission.MODERATE_POSTS)) v.row(Btn.cb(Tr.t("speed.btn.pin"), Cb.of("vs:pin", id, p.getMessageId())));
        return v.row(Btn.cb(Tr.t("btn.speed"), Cb.of("vs:list", id)));
    }

    /** The soonest of the channel's best posting slots, for a second try. */
    private Optional<Instant> nextSlot(Channel ch) {
        Report r = analysis.report(ch);
        Instant now = Instant.now();
        return r.topSlots().stream().map(s -> Insights.nextOccurrence(s.day(), s.hour(), ch.zone(), now)).min(Comparator.naturalOrder());
    }
}
