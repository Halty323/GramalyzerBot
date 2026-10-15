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

    public User(Long telegramId, String username, String firstName) {
        this.telegramId = telegramId;
        this.username = username;
        this.firstName = firstName;
        this.createdAt = Instant.now();
    }

    public User(Long id, Long telegramId, String username, String firstName, Instant createdAt, Instant lastSeenAt) {
        this.id = id;
        this.telegramId = telegramId;
        this.username = username;
        this.firstName = firstName;
        this.createdAt = createdAt;
        this.lastSeenAt = lastSeenAt;
    }

    public User(Long id, Long telegramId, String username, String firstName, Instant createdAt) {
        this.id = id;
        this.telegramId = telegramId;
        this.username = username;
        this.firstName = firstName;
        this.createdAt = createdAt;
    }

    public User(Long id, Long telegramId, String username, String firstName) {
        this.id = id;
        this.telegramId = telegramId;
        this.username = username;
        this.firstName = firstName;
    }
}