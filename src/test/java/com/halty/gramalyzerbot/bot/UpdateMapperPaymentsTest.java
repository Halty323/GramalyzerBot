package com.halty.gramalyzerbot.bot;

import com.halty.gramalyzerbot.bot.event.Events;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.payments.PreCheckoutQuery;
import org.telegram.telegrambots.meta.api.objects.payments.RefundedPayment;
import org.telegram.telegrambots.meta.api.objects.payments.SuccessfulPayment;

import static org.junit.jupiter.api.Assertions.*;

class UpdateMapperPaymentsTest {

    private final UpdateMapper mapper = new UpdateMapper();
    private final User buyer = new User(42L, "Ann", false);

    private Message privateMessage() {
        Message m = new Message();
        m.setMessageId(7);
        m.setFrom(buyer);
        m.setChat(Chat.builder().id(42L).type("private").build());
        return m;
    }

    @Test
    void preCheckoutQueries() {
        Update u = new Update();
        u.setPreCheckoutQuery(new PreCheckoutQuery("q1", buyer, "XTR", 250, "plan:CREATOR:42", null, null));
        Events.PreCheckout q = (Events.PreCheckout) mapper.map(u).orElseThrow();
        assertEquals("q1", q.id());
        assertEquals(42L, q.userId());
        assertEquals(250, q.total());
        assertEquals("plan:CREATOR:42", q.payload());
    }

    @Test
    void successfulPayments() {
        Message m = privateMessage();
        m.setSuccessfulPayment(SuccessfulPayment.builder().currency("XTR").totalAmount(250).invoicePayload("plan:CREATOR:42")
                .telegramPaymentChargeId("ch-1").providerPaymentChargeId("").subscriptionExpirationDate(1_800_000_000)
                .isRecurring(true).isFirstRecurring(true).build());
        Update u = new Update();
        u.setMessage(m);
        Events.Payment p = (Events.Payment) mapper.map(u).orElseThrow();
        assertEquals("ch-1", p.chargeId());
        assertEquals(1_800_000_000, p.expiresAt());
        assertTrue(p.recurring());
        assertTrue(p.firstRecurring());
    }

    @Test
    void refunds() {
        Message m = privateMessage();
        m.setRefundedPayment(RefundedPayment.builder().currency("XTR").totalAmount(250).invoicePayload("plan:CREATOR:42")
                .telegramPaymentChargeId("ch-1").build());
        Update u = new Update();
        u.setMessage(m);
        Events.Refund r = (Events.Refund) mapper.map(u).orElseThrow();
        assertEquals("ch-1", r.chargeId());
        assertEquals(250, r.total());
    }
}
