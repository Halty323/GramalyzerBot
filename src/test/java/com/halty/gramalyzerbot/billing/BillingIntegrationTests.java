package com.halty.gramalyzerbot.billing;

import com.halty.gramalyzerbot.bot.screen.PlanScreens;
import com.halty.gramalyzerbot.bot.ui.View;
import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.channel.ChannelService;
import com.halty.gramalyzerbot.channel.MembershipService;
import com.halty.gramalyzerbot.common.UserFacingException;
import com.halty.gramalyzerbot.i18n.Lang;
import com.halty.gramalyzerbot.i18n.Tr;
import com.halty.gramalyzerbot.user.User;
import com.halty.gramalyzerbot.user.UserRepository;
import com.halty.gramalyzerbot.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

/** Plans, limits and the bookkeeping of Stars payments, against a real (H2) database. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:billing;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "gramalyzer.demo=false", "gramalyzer.api-token=test-only-workspace-token", "telegram.bot.enabled=false", "gramalyzer.tracking.refresh-enabled=false",
        "gramalyzer.billing.enabled=true", "gramalyzer.billing.creator-stars=250", "gramalyzer.billing.pro-stars=750"})
class BillingIntegrationTests {

    @Autowired BillingService billing;
    @Autowired UserService userService;
    @Autowired UserRepository users;
    @Autowired ChannelService channels;
    @Autowired MembershipService membership;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlanScreens screens;

    private long nextChat = -1001000000000L;

    @BeforeEach
    void reset() {
        for (String table : new String[]{"payments", "channel_members", "audit_log", "channels", "users"})
            jdbc.update("DELETE FROM " + table);
    }

    private User person(long telegramId) { return userService.touch(telegramId, "person" + telegramId, "Person", "en"); }

    private Channel connect(User owner) {
        Channel ch = channels.register(nextChat--, "Channel " + nextChat, null, null);
        membership.claim(ch, owner);
        return ch;
    }

    private User reload(User u) { return users.findById(u.getId()).orElseThrow(); }

    private static Integer inDays(int days) { return (int) Instant.now().plus(Duration.ofDays(days)).getEpochSecond(); }

    @Test
    void freePlanHasRoomForOneChannel() {
        User u = person(1);
        assertEquals(Plan.FREE, billing.plan(u));
        Channel first = connect(u);
        billing.requireRoomFor(u, first.getTelegramChatId()); // reconnecting the same channel is fine
        UserFacingException e = assertThrows(UserFacingException.class, () -> billing.requireRoomFor(u, -42L));
        assertEquals("err.plan_channels", e.key());
        assertEquals(3, billing.watchlistLimit(u));
    }

    @Test
    void aFirstPaymentStartsThePlanAndRenewalsExtendIt() {
        User u = person(2);
        connect(u);
        String payload = billing.payload(Plan.CREATOR, u);

        BillingService.Paid paid = billing.paid(2, payload, 250, "charge-1", inDays(30), true, true).orElseThrow();
        assertFalse(paid.renewal());
        assertNull(paid.replacedCharge());
        u = reload(u);
        assertEquals(Plan.CREATOR, billing.plan(u));
        assertEquals("charge-1", u.getPlanChargeId());
        billing.requireRoomFor(u, -42L); // 1 of 3 used

        assertTrue(billing.paid(2, payload, 250, "charge-1", inDays(30), true, true).isEmpty(), "Telegram sent it twice");

        BillingService.Paid renewed = billing.paid(2, payload, 250, "charge-2", inDays(60), true, false).orElseThrow();
        assertTrue(renewed.renewal());
        u = reload(u);
        assertEquals("charge-1", u.getPlanChargeId(), "the subscription keeps the handle of its first payment");
        assertTrue(u.getPlanUntil().isAfter(Instant.now().plus(Duration.ofDays(59))));
    }

    @Test
    void upgradingReplacesTheOldSubscription() {
        User u = person(3);
        billing.paid(3, billing.payload(Plan.CREATOR, u), 250, "creator-1", inDays(30), true, true).orElseThrow();
        BillingService.Paid pro = billing.paid(3, billing.payload(Plan.PRO, u), 750, "pro-1", inDays(30), true, true).orElseThrow();
        assertEquals("creator-1", pro.replacedCharge());
        assertEquals(Plan.PRO, billing.plan(reload(u)));

        // the old subscription renews anyway (cancelling it failed): the better plan stays
        BillingService.Paid stray = billing.paid(3, billing.payload(Plan.CREATOR, u), 250, "creator-2", inDays(40), true, false).orElseThrow();
        assertEquals(Plan.PRO, stray.plan());
        assertEquals(Plan.PRO, billing.plan(reload(u)));
    }

    @Test
    void refundingTheCurrentPeriodEndsThePlan() {
        User u = person(4);
        billing.paid(4, billing.payload(Plan.PRO, u), 750, "pro-1", inDays(30), true, true).orElseThrow();
        BillingService.Refunded r = billing.refunded("pro-1").orElseThrow();
        assertTrue(r.planEnded());
        assertEquals("pro-1", r.endedCharge(), "its renewal must be stopped too");
        assertEquals(Plan.FREE, billing.plan(reload(u)));
        assertTrue(billing.refunded("pro-1").isEmpty(), "a refund is only recorded once");
        assertTrue(billing.refunded("no-such-charge").isEmpty());
    }

    @Test
    void plansRunOutUnlessRenewed() {
        User u = person(5);
        billing.paid(5, billing.payload(Plan.CREATOR, u), 250, "c-1", inDays(1), true, true).orElseThrow();
        u = reload(u);
        u.setPlanUntil(Instant.now().minus(Duration.ofHours(2)));
        users.save(u);
        assertEquals(Plan.CREATOR, billing.plan(reload(u)), "a renewal may arrive a little late");
        billing.setCanceled(reload(u), true);
        assertEquals(Plan.FREE, billing.plan(reload(u)), "a cancelled plan ends on time");
    }

    @Test
    void channelsBeyondTheOwnersPlanArePaused() {
        User u = person(6);
        Channel first = connect(u);
        Channel second = connect(u);
        Channel third = connect(u);
        assertEquals(OptionalInt.empty(), billing.pausedLimit(first), "the oldest channel stays");
        assertEquals(OptionalInt.of(1), billing.pausedLimit(second));
        assertFalse(billing.covers(third));

        billing.paid(6, billing.payload(Plan.CREATOR, u), 250, "c-6", inDays(30), true, true).orElseThrow();
        assertTrue(billing.covers(second));
        assertTrue(billing.covers(third));
    }

    @Test
    void botAdminsHaveNoLimits() {
        User u = person(7);
        u.setBotAdmin(true);
        users.save(u);
        connect(u);
        connect(u);
        billing.requireRoomFor(reload(u), -42L);
        assertEquals(Integer.MAX_VALUE, billing.watchlistLimit(reload(u)));
    }

    @Test
    void checkoutOnlyAcceptsOurOwnOrders() {
        User u = person(8);
        String payload = billing.payload(Plan.CREATOR, u);
        assertEquals(Optional.empty(), billing.refusal(8, "XTR", payload));
        assertEquals(Optional.of("pay.err.invalid"), billing.refusal(9, "XTR", payload), "someone else's invoice");
        assertEquals(Optional.of("pay.err.invalid"), billing.refusal(8, "USD", payload));
        assertEquals(Optional.of("pay.err.invalid"), billing.refusal(8, "XTR", "plan:FREE:8"));
        assertEquals(Optional.of("pay.err.invalid"), billing.refusal(8, "XTR", "garbage"));
        assertTrue(billing.paid(8, "garbage", 250, "x-1", null, false, false).isEmpty());
    }

    @Test
    void planScreensRenderInEveryLanguage() {
        User u = person(10);
        Channel ch = connect(u);
        billing.paid(10, billing.payload(Plan.CREATOR, u), 250, "c-10", inDays(30), true, true).orElseThrow();
        User paid = reload(u);
        for (Lang lang : Lang.values()) {
            Tr.in(lang, () -> {
                View home = screens.home(paid, 1, 2);
                assertTrue(home.html().contains("Creator"), lang + ": " + home.html());
                assertTrue(home.html().contains("250"), lang + ": " + home.html());
                assertFalse(home.html().contains("{"), lang + " left a placeholder: " + home.html());
                // the other paid plan is on offer, then cancel, then help and back side by side
                assertEquals(3, home.rows().size(), lang + ": " + home.rows());
                assertEquals("bill:buy:PRO", home.rows().get(0).get(0).data());
                assertTrue(screens.checkout(Plan.PRO, "https://t.me/$abc").rows().get(0).get(0).isLink());
                assertTrue(screens.paused(ch, 1).html().contains(ch.getTitle()));
                assertFalse(screens.support(billing.recent(paid)).html().contains("{"));
                return null;
            });
        }
        billing.setCanceled(paid, true);
        View cancelled = Tr.in(Lang.EN, () -> screens.home(reload(u), 1, 2));
        assertTrue(cancelled.html().contains("renewal is off"), cancelled.html());
    }
}
