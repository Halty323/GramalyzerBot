package com.halty.gramalyzerbot.bot.flow;

import com.halty.gramalyzerbot.billing.BillingService;
import com.halty.gramalyzerbot.billing.Payment;
import com.halty.gramalyzerbot.billing.Plan;
import com.halty.gramalyzerbot.bot.BotApi;
import com.halty.gramalyzerbot.bot.event.Events;
import com.halty.gramalyzerbot.bot.screen.PlanScreens;
import com.halty.gramalyzerbot.bot.ui.Cb;
import com.halty.gramalyzerbot.bot.ui.Fmt;
import com.halty.gramalyzerbot.bot.ui.View;
import com.halty.gramalyzerbot.i18n.Lang;
import com.halty.gramalyzerbot.i18n.Tr;
import com.halty.gramalyzerbot.tracking.TrackingService;
import com.halty.gramalyzerbot.user.User;
import com.halty.gramalyzerbot.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Paid plans in the bot: the plan screen, Telegram Stars checkout, receipts, refunds and /paysupport (which Telegram
 * requires of every bot that sells something).
 * <p>
 * The flow: a pay button opens a Stars subscription invoice; Telegram asks {@link #preCheckout} whether to go ahead,
 * charges the person, and sends {@link #paid} – again every 30 days when the subscription renews.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BillingFlow {

    private final Sessions sessions;
    private final UserService users;
    private final BillingService billing;
    private final TrackingService tracking;
    private final PlanScreens screens;
    private final BotApi gateway;

    // ------------------------------------------------------------------ screens

    public View home(User u) {
        int channels = billing.owned(u).size();
        int watched = (int) tracking.watchCount(TrackingService.owner(u));
        return screens.home(u, channels, watched);
    }

    public void showHome(User u, long chat) { gateway.send(chat, home(u)); }

    /** Handles a {@code bill:…} button; returns the toast to show, or null. */
    public String callback(User u, long chat, int messageId, Cb.Args a) {
        switch (a.get(1)) {
            case "home" -> gateway.edit(chat, messageId, home(u));
            case "buy" -> {
                if (!billing.enabled()) return Tr.t("plan.off");
                Plan p = Plan.valueOf(a.get(2));
                if (!p.paid()) throw new IllegalArgumentException("not for sale: " + p);
                if (billing.plan(u) == p && !u.isPlanCanceled()) return Tr.t("plan.already", PlanScreens.name(p));
                Optional<String> link = gateway.starsSubscriptionLink(
                        Tr.t("pay.invoice.title", PlanScreens.name(p)),
                        Tr.t("pay.invoice.description", p.channels(), p.watchlist()),
                        billing.payload(p, u),
                        Tr.t("pay.invoice.label", PlanScreens.name(p)),
                        billing.stars(p));
                if (link.isEmpty()) return Tr.t("pay.err.link");
                gateway.edit(chat, messageId, screens.checkout(p, link.get()));
            }
            case "cancel", "resume" -> {
                boolean cancel = a.get(1).equals("cancel");
                if (u.getPlanChargeId() == null || !billing.plan(u).paid()) return Tr.t("plan.nothing_to_change");
                if (!gateway.setStarSubscriptionCanceled(u.getTelegramId(), u.getPlanChargeId(), cancel)) return Tr.t("pay.err.telegram");
                billing.setCanceled(u, cancel);
                gateway.edit(chat, messageId, home(u));
                return Tr.t(cancel ? "plan.canceled" : "plan.resumed");
            }
            case "support" -> gateway.edit(chat, messageId, screens.support(billing.recent(u)));
            default -> throw new IllegalArgumentException("unknown bill " + a.get(1));
        }
        return null;
    }

    // ------------------------------------------------------------------ Telegram's payment updates

    public void preCheckout(Events.PreCheckout q) {
        Optional<String> refusal = billing.refusal(q.userId(), q.currency(), q.payload());
        Lang lang = users.findByTelegramId(q.userId()).map(Lang::of).orElse(Lang.EN);
        gateway.answerPreCheckout(q.id(), refusal.map(key -> Tr.in(lang, () -> Tr.t(key))).orElse(null));
    }

    public void paid(Events.Payment p) {
        Optional<User> opened = sessions.open(p.userId(), p.username(), p.firstName(), p.lang(), p.chatId());
        // Money was taken: record it even for a banned person, so it can be found and refunded.
        Optional<BillingService.Paid> paid = billing.paid(p.userId(), p.payload(), p.total(), p.chargeId(), p.expiresAt(),
                p.recurring(), p.firstRecurring());
        if (paid.isEmpty()) return;
        BillingService.Paid r = paid.get();
        log.info("Payment {}: {} Stars from {} for {} until {}{}", p.chargeId(), p.total(), p.userId(), r.plan(), r.until(),
                r.renewal() ? " (renewal)" : "");
        if (r.replacedCharge() != null && !gateway.setStarSubscriptionCanceled(p.userId(), r.replacedCharge(), true))
            log.warn("Could not cancel the replaced subscription {} of {}", r.replacedCharge(), p.userId());
        if (opened.isEmpty()) return;
        if (r.renewal()) gateway.send(p.chatId(), View.of(Tr.t("plan.renewed", PlanScreens.name(r.plan()), Fmt.date(r.until()))));
        else gateway.send(p.chatId(), screens.thanks(r.plan(), r.until()));
    }

    public void refunded(Events.Refund r) {
        Optional<BillingService.Refunded> done = billing.refunded(r.chargeId());
        if (done.isEmpty()) return;
        afterRefund(done.get());
        sessions.open(r.userId(), r.username(), r.firstName(), r.lang(), r.chatId())
                .ifPresent(u -> gateway.send(r.chatId(), View.of(Tr.t(done.get().planEnded() ? "pay.refunded_ended" : "pay.refunded", r.total()))));
    }

    private void afterRefund(BillingService.Refunded r) {
        log.info("Payment {} refunded ({} Stars, user {})", r.payment().getChargeId(), r.payment().getStars(), r.user().getTelegramId());
        if (r.endedCharge() != null) gateway.setStarSubscriptionCanceled(r.user().getTelegramId(), r.endedCharge(), true);
    }

    // ------------------------------------------------------------------ support

    /**
     * {@code /paysupport}: on its own, explains how to get help and lists recent payments; with text, passes the message
     * on to the bot's admins together with those payments.
     */
    public void support(User u, long chat, String text) {
        if (text.isBlank()) {
            gateway.send(chat, screens.support(billing.recent(u)));
            return;
        }
        var admins = users.botAdmins();
        if (admins.isEmpty()) {
            gateway.send(chat, View.of(Tr.t("pay.support.nobody")));
            return;
        }
        for (User admin : admins) {
            View v = Tr.in(Lang.of(admin), () -> {
                View m = View.of(Tr.t("pay.support.forward", Fmt.esc(u.displayName()), u.getTelegramId(),
                        u.getUsername() == null ? "" : " · @" + Fmt.esc(u.getUsername())));
                m.line("<i>" + Fmt.esc(Fmt.trunc(text, 1500)) + "</i>");
                for (Payment p : billing.recent(u)) m.line(PlanScreens.receipt(p));
                return m.line(Tr.t("pay.support.refund_hint"));
            });
            gateway.send(admin.getTelegramId(), v);
        }
        gateway.send(chat, View.of(Tr.t("pay.support.sent")));
    }

    /** {@code /refund <charge id>} for bot admins: gives the Stars back and ends the plan if that payment paid for it. */
    public void refund(User admin, long chat, String chargeId) {
        if (chargeId.isBlank()) {
            gateway.send(chat, View.of(Tr.t("pay.refund.usage")));
            return;
        }
        Optional<Payment> p = billing.payment(chargeId.trim());
        if (p.isEmpty()) {
            gateway.send(chat, View.of(Tr.t("pay.refund.unknown")));
            return;
        }
        Optional<User> buyer = users.findById(p.get().getUserId());
        if (p.get().getRefundedAt() != null || buyer.isEmpty()) {
            gateway.send(chat, View.of(Tr.t("pay.refund.already")));
            return;
        }
        if (!gateway.refundStars(buyer.get().getTelegramId(), p.get().getChargeId())) {
            gateway.send(chat, View.of(Tr.t("pay.refund.failed")));
            return;
        }
        // Telegram also sends the buyer a refund update, which then finds the payment already marked and stays quiet.
        Optional<BillingService.Refunded> done = billing.refunded(p.get().getChargeId());
        done.ifPresent(this::afterRefund);
        boolean ended = done.map(BillingService.Refunded::planEnded).orElse(false);
        User b = buyer.get();
        gateway.send(b.getTelegramId(), Tr.in(Lang.of(b), () -> View.of(Tr.t(ended ? "pay.refunded_ended" : "pay.refunded", p.get().getStars()))));
        gateway.send(chat, View.of(Tr.t("pay.refund.done", p.get().getStars(), Fmt.esc(b.displayName()))));
    }
}
