package com.halty.gramalyzerbot.bot.screen;

import com.halty.gramalyzerbot.billing.BillingService;
import com.halty.gramalyzerbot.billing.Payment;
import com.halty.gramalyzerbot.billing.Plan;
import com.halty.gramalyzerbot.bot.ui.*;
import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.i18n.Tr;
import com.halty.gramalyzerbot.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/** The plan screen, the pay button, payment receipts and the "this channel is paused" notice. */
@Component
@RequiredArgsConstructor
public class PlanScreens {

    private final BillingService billing;

    public static String name(Plan p) { return Tr.t("lbl.plan." + p.name()); }

    public View home(User u, int channelsUsed, int watchlistUsed) {
        View v = View.of(Tr.t("plan.title"));
        if (!billing.enabled()) return v.blank().line(Tr.t("plan.off")).row(Btn.cb(Tr.t("btn.back"), "ch:list"));

        Plan current = billing.plan(u);
        v.line(Tr.t("plan.current", name(current)));
        if (current.paid()) {
            String until = Fmt.date(u.getPlanUntil());
            v.line(u.isPlanCanceled() ? Tr.t("plan.ends", until) : Tr.t("plan.renews", until, billing.stars(current)));
        }
        if (u.isBotAdmin()) v.line(Tr.t("plan.admin"));
        else v.line(Tr.t("plan.usage", channelsUsed, current.channels(), watchlistUsed, current.watchlist()));
        v.blank();
        for (Plan p : Plan.values()) v.line(offer(p, p == current));
        v.blank().line(Tr.t("plan.how"));

        for (Plan p : Plan.values()) {
            if (!p.paid() || p == current) continue;
            v.row(Btn.cb(Tr.t("plan.btn.get", name(p), billing.stars(p)), Cb.of("bill:buy", p.name())));
        }
        if (current.paid()) {
            if (u.isPlanCanceled()) v.row(Btn.cb(Tr.t("plan.btn.resume"), "bill:resume"));
            else if (u.getPlanChargeId() != null) v.row(Btn.cb(Tr.t("plan.btn.cancel"), "bill:cancel"));
        }
        return v.row(Btn.cb(Tr.t("plan.btn.support"), "bill:support"), Btn.cb(Tr.t("btn.back"), "ch:list"));
    }

    private String offer(Plan p, boolean current) {
        String limits = Tr.t("plan.limits", p.channels(), p.watchlist());
        String line = p.paid()
                ? Tr.t("plan.offer_paid", name(p), billing.stars(p), limits)
                : Tr.t("plan.offer_free", name(p), limits);
        return current ? "✅ " + line : "▫️ " + line;
    }

    /** What the person is about to buy, with the button that opens Telegram's payment sheet. */
    public View checkout(Plan p, String link) {
        return View.of(Tr.t("plan.checkout.title", name(p), billing.stars(p)))
                .line(Tr.t("plan.limits", p.channels(), p.watchlist()))
                .blank()
                .line(Tr.t("plan.checkout.terms"))
                .row(Btn.link(Tr.t("plan.btn.pay", billing.stars(p)), link))
                .row(Btn.cb(Tr.t("btn.back"), "bill:home"));
    }

    public View thanks(Plan p, Instant until) {
        return View.of(Tr.t("plan.thanks", name(p)))
                .line(Tr.t("plan.thanks_until", Fmt.date(until)))
                .row(Btn.cb(Tr.t("start.btn.connect_another"), "ch:connect"))
                .row(Btn.cb(Tr.t("plan.btn.my_plan"), "bill:home"));
    }

    /** Shown in place of a channel's insights when its owner's plan no longer covers it. */
    public View paused(Channel ch, int limit) {
        return View.of(Tr.t("plan.paused", Fmt.esc(ch.getTitle()), limit))
                .line(Tr.t("plan.paused_hint"))
                .row(Btn.cb(Tr.t("plan.btn.upgrade"), "bill:home"))
                .row(Btn.cb(Tr.t("btn.settings"), Cb.of("set:home", ch.getId())), Btn.cb(Tr.t("btn.back"), "ch:list"));
    }

    public View support(List<Payment> recent) {
        View v = View.of(Tr.t("pay.support.title")).blank().line(Tr.t("pay.support.how"));
        if (!recent.isEmpty()) {
            v.blank().line(Tr.t("pay.support.recent"));
            for (Payment p : recent) v.line(receipt(p));
        }
        return v.row(Btn.cb(Tr.t("plan.btn.my_plan"), "bill:home"));
    }

    /** One payment, for the person and for the admins: date, plan, Stars and the charge id a refund needs. */
    public static String receipt(Payment p) {
        String line = "• " + Fmt.date(p.getPaidAt()) + " · " + name(p.getPlan()) + " · " + p.getStars() + " ⭐ · <code>"
                + Fmt.esc(p.getChargeId()) + "</code>";
        return p.getRefundedAt() != null ? line + " · " + Tr.t("pay.refunded_mark") : line;
    }
}
