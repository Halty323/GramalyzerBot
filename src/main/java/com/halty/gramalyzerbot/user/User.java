package com.halty.gramalyzerbot.user;

import com.halty.gramalyzerbot.billing.Plan;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "telegram_id", nullable = false, unique = true)
    private Long telegramId;

    private String username;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    /** Bot-wide administrator (can open /admin). */
    @Column(name = "bot_admin", nullable = false)
    private boolean botAdmin;

    @Column(nullable = false)
    private boolean banned;

    /** The language the person's Telegram app reports, e.g. "pt-br". */
    @Column(name = "language_code")
    private String languageCode;

    /** The bot's language the person picked themselves (a {@code Lang} code); null to follow {@link #languageCode}. */
    private String language;

    /** The channel the person is currently looking at. */
    @Column(name = "active_channel_id")
    private Long activeChannelId;

    /** Pending multi-step prompt (e.g. "type your city"), with its argument and expiry. */
    private String state;

    @Column(name = "state_arg")
    private String stateArg;

    @Column(name = "state_expires_at")
    private Instant stateExpiresAt;

    /** The plan last paid for; {@code BillingService.plan} says whether it's still in force. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Plan plan = Plan.FREE;

    @Column(name = "plan_until")
    private Instant planUntil;

    /** The first payment of the running Stars subscription, which Telegram uses to cancel or resume it. */
    @Column(name = "plan_charge_id")
    private String planChargeId;

    /** The person stopped automatic renewal; the plan still runs until {@link #planUntil}. */
    @Column(name = "plan_canceled", nullable = false)
    private boolean planCanceled;

    public User(Long telegramId, String username, String firstName) {
        this.telegramId = telegramId;
        this.username = username;
        this.firstName = firstName;
        this.createdAt = Instant.now();
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public String displayName() {
        if (firstName != null && !firstName.isBlank()) return firstName;
        if (username != null && !username.isBlank()) return "@" + username;
        return "User " + telegramId;
    }
}
