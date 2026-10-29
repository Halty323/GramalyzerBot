package com.halty.gramalyzerbot.bot.screen;

import com.halty.gramalyzerbot.billing.BillingService;
import com.halty.gramalyzerbot.bot.ui.*;
import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.channel.Permission;
import com.halty.gramalyzerbot.channel.Role;
import com.halty.gramalyzerbot.channel.SubscriberService;
import com.halty.gramalyzerbot.growth.GrowthService;
import com.halty.gramalyzerbot.growth.GrowthService.LinkStat;
import com.halty.gramalyzerbot.growth.GrowthService.PostEffect;
import com.halty.gramalyzerbot.growth.PromoLink;
import com.halty.gramalyzerbot.i18n.Tr;
import com.halty.gramalyzerbot.post.Post;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;

/** Subscriber growth: the count over time, who joined and left after which post, and promo links. */
@Component
@RequiredArgsConstructor
public class GrowthScreens {

    private static final int SHOWN_POSTS = 3;
    private static final int SHOWN_LINKS = 8;

    private final SubscriberService subscribers;
    private final GrowthService growth;
    private final BillingService billing;
    private final PlanScreens plans;

    public View growth(Channel ch, int days) {
        OptionalInt paused = billing.pausedLimit(ch);
        if (paused.isPresent()) return plans.paused(ch, paused.getAsInt());
        SubscriberService.Growth g = subscribers.growth(ch, days);
        View v = new View().line(Tr.t("growth.title")).line("<i>" + Fmt.esc(ch.getTitle()) + " · " + Labels.window(days) + "</i>").blank();
        if (g.current() != null) v.line(Tr.t("growth.now", Fmt.num(g.current())));
        if (g.series().size() < 2 || g.change() == null) {
            v.line(Tr.t("growth.need_days"));
        } else {
            double perDay = g.change() / (double) days;
            v.line((g.change() >= 0 ? "▲ " : "▼ ") + Tr.t("growth.change", Fmt.signed(g.change()), Labels.window(days), (perDay >= 0 ? "+" : "") + Fmt.one(perDay)));
            v.line("<pre>" + Fmt.sparkline(InsightScreens.downsample(g.series(), 40)) + "</pre>");
            v.line("<i>" + Fmt.num(g.series().get(0)) + " → " + Fmt.num(g.series().get(g.series().size() - 1)) + "</i>");
        }

        GrowthService.Report r = growth.report(ch, days);
        v.blank();
        if (!r.tracking()) {
            v.line(Tr.t("growth.flow_waiting"));
        } else {
            v.line(Tr.t("growth.flow", Fmt.num(r.joined()), Fmt.num(r.left()), Fmt.signed(r.net())));
            if (r.since().isAfter(Instant.now().minus(Duration.ofDays(days)))) v.line("<i>" + Tr.t("growth.since", Fmt.date(r.since())) + "</i>");
            if (r.fromLinks() > 0) v.line(Tr.t("growth.from_links", Fmt.num(r.fromLinks())));
            effects(v, Tr.t("growth.gainers"), r.gainers(), ch);
            effects(v, Tr.t("growth.losers"), r.losers(), ch);
            if (!r.losers().isEmpty()) v.line("<i>" + Tr.t("growth.losers_hint") + "</i>");
        }

        long id = ch.getId();
        List<Btn> chips = new ArrayList<>();
        for (int d : new int[]{7, 30, 90}) chips.add(Btn.cb((d == days ? "✓ " : "") + Labels.windowShort(d), Cb.of("nav:growth", id, d)));
        v.row(chips.toArray(new Btn[0]));
        v.row(Btn.cb(Tr.t("btn.promo"), Cb.of("pr:list", id)));
        return v.row(Btn.cb(Tr.t("btn.dashboard"), Cb.of("nav:home", id)));
    }

    private static void effects(View v, String title, List<PostEffect> list, Channel ch) {
        if (list.isEmpty()) return;
        v.blank().line("<b>" + title + "</b>");
        for (PostEffect p : list.subList(0, Math.min(SHOWN_POSTS, list.size()))) {
            String what = p.preview().isBlank() ? Labels.kind(p.kind()) : Labels.kindEmoji(p.kind()) + " " + Fmt.trunc(p.preview(), 50);
            String label = Fmt.esc(what);
            if (p.url() != null) label = "<a href=\"" + p.url() + "\">" + label + "</a>";
            v.line("• " + label);
            v.line("     " + Tr.t("growth.effect", Fmt.num(p.joined()), Fmt.num(p.left()), Fmt.one(p.hours()))
                    + " · " + InsightScreens.when(p.postedAt().atZone(ch.zone())));
        }
    }

    // ------------------------------------------------------------------ promo links

    public View promo(Channel ch, Role role) {
        List<LinkStat> all = growth.linkStats(ch);
        View v = new View().line(Tr.t("promo.title")).line("<i>" + Fmt.esc(ch.getTitle()) + "</i>").blank();
        long id = ch.getId();
        boolean manage = role.can(Permission.CHANGE_SETTINGS);
        if (all.isEmpty()) {
            v.line(Tr.t("promo.empty"));
        } else {
            v.line(Tr.t("promo.hint")).blank();
            List<Btn> revoke = new ArrayList<>();
            for (int i = 0; i < Math.min(SHOWN_LINKS, all.size()); i++) {
                LinkStat l = all.get(i);
                v.line("<b>" + (i + 1) + ". " + Fmt.esc(l.name()) + "</b>" + (l.revoked() ? "  <i>" + Tr.t("promo.revoked") + "</i>" : ""));
                if (!l.revoked()) v.line("<code>" + Fmt.esc(l.url()) + "</code>");
                String line = Tr.t("promo.counts", Fmt.num(l.joined()), Fmt.num(l.stayed()), Fmt.num(l.left()));
                if (l.cost() != null) line += " · " + Tr.t("promo.cost", money(l.cost(), l.currency()),
                        l.costPerSubscriber() == null ? "–" : money(l.costPerSubscriber(), l.currency()));
                v.line(line);
                if (manage && !l.revoked()) revoke.add(Btn.cb("🗑 " + (i + 1), Cb.of("pr:rv", id, l.id())));
            }
            for (int i = 0; i < revoke.size(); i += 4) v.row(revoke.subList(i, Math.min(i + 4, revoke.size())).toArray(new Btn[0]));
        }
        if (manage) v.row(Btn.cb(Tr.t("promo.btn.new"), Cb.of("pr:new", id)));
        return v.row(Btn.cb(Tr.t("btn.back"), Cb.of("nav:growth", id, 30)));
    }

    public View promoPrompt(Channel ch) {
        return new View().line(Tr.t("promo.new_title")).blank().line(Tr.t("promo.new_text")).line(Tr.t("promo.new_example"))
                .row(Btn.cb(Tr.t("btn.cancel"), Cb.of("pr:list", ch.getId())));
    }

    public View created(Channel ch, PromoLink l) {
        return new View().line(Tr.t("promo.created", Fmt.esc(l.getName()))).line("<code>" + Fmt.esc(l.getUrl()) + "</code>")
                .blank().line(Tr.t("promo.created_hint"))
                .row(Btn.cb(Tr.t("btn.promo"), Cb.of("pr:list", ch.getId())));
    }

    public View confirmRevoke(Channel ch, PromoLink l) {
        long id = ch.getId();
        return new View().line(Tr.t("promo.revoke_q", Fmt.esc(l.getName()))).line(Tr.t("promo.revoke_hint"))
                .row(Btn.cb(Tr.t("promo.btn.revoke"), Cb.of("pr:rvgo", id, l.getId())), Btn.cb(Tr.t("btn.cancel"), Cb.of("pr:list", id)));
    }

    /** The alert sent when people leave in a wave. */
    public View wave(GrowthService.Wave w) {
        Channel ch = w.channel();
        View v = View.of(Tr.t("wave.title", Fmt.esc(ch.getTitle()))).line(Tr.t("wave.counts", Fmt.num(w.left()), Fmt.num(w.joined())));
        Post p = w.lastPost();
        if (p != null) {
            String body = p.getBody() == null || p.getBody().isBlank() ? Labels.kind(p.getKind().name()) : Fmt.trunc(p.getBody().strip(), 70);
            v.line(Tr.t("wave.last_post", Fmt.esc(body), Fmt.ago(p.getPostedAt(), Instant.now())));
        }
        v.line("<i>" + Tr.t("wave.hint") + "</i>");
        return v.row(Btn.cb(Tr.t("btn.growth"), Cb.of("nav:growth", ch.getId(), 7)));
    }

    /** "20$", "$20", "450 UAH", "2.50 €"… as the person typed the currency. */
    static String money(double amount, String currency) {
        String n = amount == Math.rint(amount) ? String.valueOf((long) amount) : String.format(Locale.US, "%.2f", amount);
        if (currency == null || currency.isBlank()) return n;
        return currency.length() == 1 && "$£".contains(currency) ? currency + n : n + " " + currency;
    }
}
