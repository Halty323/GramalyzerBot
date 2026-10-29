package com.halty.gramalyzerbot.channel;

import com.halty.gramalyzerbot.analytics.Metric;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;

@Entity
@Table(name = "channels")
@Getter
@Setter
@NoArgsConstructor
public class Channel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "telegram_chat_id", nullable = false, unique = true)
    private Long telegramChatId;

    @Column(nullable = false)
    private String title;

    private String username;

    @Column(nullable = false)
    private String timezone = "UTC";

    @Column(name = "timezone_confirmed", nullable = false)
    private boolean timezoneConfirmed;

    /** Analysis look-back in days; 0 = all time. */
    @Column(name = "window_days", nullable = false)
    private int windowDays = 90;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Metric metric = Metric.ENGAGEMENT;

    @Enumerated(EnumType.STRING)
    @Column(name = "digest_day", nullable = false)
    private DayOfWeek digestDay = DayOfWeek.MONDAY;

    @Column(name = "digest_hour", nullable = false)
    private int digestHour = 9;

    @Column(name = "nudge_lead_minutes", nullable = false)
    private int nudgeLeadMinutes = 30;

    @Column(name = "nudge_gap_hours", nullable = false)
    private int nudgeGapHours = 6;

    private Integer subscribers;

    @Column(name = "subscribers_checked_at")
    private Instant subscribersCheckedAt;

    @Column(name = "discussion_chat_id")
    private Long discussionChatId;

    /** The CRM channel (workspace_channels.id) this channel belongs to; null until the CRM has seen it. */
    @Column(name = "workspace_channel_id", unique = true)
    private String workspaceChannelId;

    @Column(nullable = false)
    private boolean demo;

    /** False once the bot has been removed from the channel. */
    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "history_imported", nullable = false)
    private boolean historyImported;

    @Column(name = "connected_at", nullable = false)
    private Instant connectedAt;

    @Column(name = "last_post_event_at")
    private Instant lastPostEventAt;

    @Column(name = "last_reaction_event_at")
    private Instant lastReactionEventAt;

    @Column(name = "last_digest_at")
    private Instant lastDigestAt;

    @Column(name = "last_nudge_at")
    private Instant lastNudgeAt;

    @Column(name = "last_leave_alert_at")
    private Instant lastLeaveAlertAt;

    @PrePersist
    void onCreate() {
        if (connectedAt == null) connectedAt = Instant.now();
    }

    public ZoneId zone() { return TimeZones.parseOrUtc(timezone); }
}
