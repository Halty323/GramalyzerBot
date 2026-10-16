package com.halty.gramalyzerbot.channel;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** One line in a channel's activity log ("who changed what"). */
@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
public class AuditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(nullable = false)
    private String action;

    private String details;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
