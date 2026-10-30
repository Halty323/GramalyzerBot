package com.halty.gramalyzerbot.bot.screen;

import com.halty.gramalyzerbot.analytics.TextFeatures;
import com.halty.gramalyzerbot.bot.ui.*;
import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.channel.Permission;
import com.halty.gramalyzerbot.channel.Role;
import com.halty.gramalyzerbot.i18n.Tr;
import com.halty.gramalyzerbot.planner.DraftScore;
import com.halty.gramalyzerbot.planner.DraftScore.Factor;
import com.halty.gramalyzerbot.planner.PlannerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.util.List;
import java.util.stream.Collectors;

/** A draft's score before it's posted, and the channel's best old posts to repost. */
@Component
@RequiredArgsConstructor
public class PlannerScreens {

    private final PlannerService planner;

    public View prompt(Channel ch) {
        return View.of(Tr.t("draft.prompt", Fmt.esc(ch.getTitle())));
    }

    public View score(Channel ch, String text, String kind) {
        DraftScore.Result r = planner.score(ch, new DraftScore.Draft(text, kind, TextFeatures.hasLink(text), null));
        View v = new View();
        if (r.status().equals("learning")) {
            v.line(Tr.t("draft.title_learning")).line(Tr.t("draft.learning", r.postsRead()));
        } else {
            v.line(Tr.t("draft.title", r.score())).line(Fmt.progress(r.score(), 100, 10))
                    .line(Tr.t("draft.expected", Fmt.lift(r.expected())) + (r.potential() > r.expected() + 0.05 ? " · " + Tr.t("draft.potential", Fmt.lift(r.potential())) : ""))
                    .blank();
            for (Factor f : r.factors()) v.line(line(f, ch));
        }
        List<PlannerService.Slot> slots = planner.free(ch, ch.getWorkspaceChannelId(), null, 3);
        if (!slots.isEmpty()) {
            v.blank().line(Tr.t("draft.slots", slots.stream().map(s -> InsightScreens.when(s.at().atZone(ch.zone()))
                    + (s.guess() ? "" : " (" + Fmt.lift(s.lift()) + ")")).collect(Collectors.joining(" · "))));
        }
        return v.blank().line("<i>" + Tr.t("draft.again") + "</i>");
    }

    private static String line(Factor f, Channel ch) {
        String what = switch (f.dimension()) {
            case FORMAT -> Labels.kind(f.key());
            case LENGTH -> "📏 " + Labels.length(f.key());
            case LINK -> "🔗 " + Labels.link(f.key());
            case TIME -> "🕓 " + slot(f.key());
            case HASHTAGS -> f.key().isEmpty() ? "#️⃣ " + Tr.t("draft.no_hashtags") : "#️⃣ " + Fmt.esc(f.key());
            case KEYWORDS -> "💬 " + Fmt.esc(f.key());
        };
        String icon = f.weak() ? "⚠️" : f.lift() >= 1.05 ? "✅" : "▫️";
        String out = icon + " " + what + (f.key().isEmpty() ? "" : " — " + Fmt.lift(f.lift()));
        if (f.weak()) {
            String better = switch (f.dimension()) {
                case FORMAT -> Labels.kindName(f.bestKey());
                case LENGTH -> Labels.length(f.bestKey());
                case LINK -> Labels.link(f.bestKey());
                case TIME -> slot(f.bestKey());
                default -> Fmt.esc(f.bestKey());
            };
            out += "\n     " + Tr.t("draft.better", better, Fmt.lift(f.bestLift()));
        }
        return out;
    }

    private static String slot(String key) {
        String[] dh = key.split(" ");
        return Fmt.dayShort(DayOfWeek.valueOf(dh[0])) + " " + Fmt.hour(Integer.parseInt(dh[1]));
    }

    public View evergreen(Channel ch, Role role) {
        long id = ch.getId();
        List<PlannerService.Evergreen> list = planner.evergreen(ch, ch.getWorkspaceChannelId());
        View v = new View().line(Tr.t("ever.title")).line("<i>" + Fmt.esc(ch.getTitle()) + "</i>").blank();
        if (list.isEmpty()) {
            v.line(Tr.t("ever.empty"));
        } else {
            v.line(Tr.t("ever.text")).blank();
            int i = 1;
            for (PlannerService.Evergreen e : list) {
                String what = Fmt.esc(e.preview().isBlank() ? Labels.kind(e.kind()) : Fmt.trunc(e.preview(), 60));
                if (e.url() != null) what = "<a href=\"" + e.url() + "\">" + what + "</a>";
                v.line(i++ + ". " + what + " — " + Fmt.lift(e.lift()) + " · " + Fmt.date(e.postedAt()));
                if (e.queuedAt() != null) v.line("     " + Tr.t("ever.queued_for", InsightScreens.when(e.queuedAt().atZone(ch.zone()))));
            }
            if (role.can(Permission.MODERATE_POSTS) && !ch.isDemo()) {
                i = 1;
                List<Btn> row = new java.util.ArrayList<>();
                for (PlannerService.Evergreen e : list) {
                    if (e.queuedAt() == null) row.add(Btn.cb(Tr.t("ever.btn", i), Cb.of("ev:rp", id, e.messageId())));
                    i++;
                    if (row.size() == 3) { v.row(row.toArray(Btn[]::new)); row.clear(); }
                }
                if (!row.isEmpty()) v.row(row.toArray(Btn[]::new));
            }
            v.blank().line("<i>" + Tr.t("ever.legend") + "</i>");
        }
        return v.row(Btn.cb(Tr.t("btn.refresh"), Cb.of("ev:list", id)), Btn.cb(Tr.t("btn.dashboard"), Cb.of("nav:home", id)));
    }
}
