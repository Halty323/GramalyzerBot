package com.halty.gramalyzerbot.billing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** One Stars payment: the first month of a subscription or a renewal. Kept for support and refunds. */
@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Plan plan;

    @Column(nullable = false)
    private int stars;

    /** Telegram's id for the payment; needed to refund it, and (for the first payment) to cancel the subscription. */
    @Column(name = "charge_id", nullable = false, unique = true)
    private String chargeId;

    /** True for a renewal Telegram charged on its own, false for the payment the person made. */
    @Column(nullable = false)
    private boolean renewal;

    @Column(name = "paid_at", nullable = false)
    private Instant paidAt;

    /** Until when this payment pays for the plan. */
    @Column(name = "paid_until", nullable = false)
    private Instant paidUntil;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    public Payment(Long userId, Plan plan, int stars, String chargeId, boolean renewal, Instant paidAt, Instant paidUntil) {
        this.userId = userId;
        this.plan = plan;
        this.stars = stars;
        this.chargeId = chargeId;
        this.renewal = renewal;
        this.paidAt = paidAt;
        this.paidUntil = paidUntil;
    }
}
