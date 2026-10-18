package com.halty.gramalyzerbot.tracking;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** A public channel as last seen from outside: its web preview and, when the bot runs, the Bot API. */
@Entity
@Table(name = "tracked_channels")
@Getter
@Setter
@NoArgsConstructor
public class TrackedChannel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Lower-case, without "@". */
    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String title;

    @Column(length = 2048)
    private String description;

    @Column(name = "photo_url", length = 1024)
    private String photoUrl;

    private Integer subscribers;

    @Column(nullable = false)
    private boolean verified;

    /** The media counters Telegram shows on the channel's page. */
    private Integer photos;
    private Integer videos;
    private Integer files;
    private Integer links;

    @Column(name = "telegram_chat_id")
    private Long telegramChatId;

    /** Sample data; never fetched from Telegram. */
    @Column(nullable = false)
    private boolean demo;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "fetched_at")
    private Instant fetchedAt;

    /** Why the last refresh failed (a {@link TrackingException.Code} name), or null. */
    @Column(name = "last_error")
    private String lastError;

    public TrackedChannel(String username, String title) {
        this.username = username;
        this.title = title;
        this.createdAt = Instant.now();
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
