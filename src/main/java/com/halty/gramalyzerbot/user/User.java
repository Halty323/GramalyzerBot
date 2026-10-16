package com.halty.gramalyzerbot.user;

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

    @Column(name = "language_code")
    private String languageCode;

    /** The channel the person is currently looking at. */
    @Column(name = "active_channel_id")
    private Long activeChannelId;

    /** Pending multi-step prompt (e.g. "type your city"), with its argument and expiry. */
    private String state;

    @Column(name = "state_arg")
    private String stateArg;

    @Column(name = "state_expires_at")
    private Instant stateExpiresAt;

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
