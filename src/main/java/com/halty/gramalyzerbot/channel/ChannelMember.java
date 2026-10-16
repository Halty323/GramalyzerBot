package com.halty.gramalyzerbot.channel;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "channel_members")
@Getter
@Setter
@NoArgsConstructor
public class ChannelMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "notify_digest", nullable = false)
    private boolean notifyDigest = true;

    @Column(name = "notify_nudges", nullable = false)
    private boolean notifyNudges = false;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    @Column(name = "added_by")
    private Long addedBy;

    public ChannelMember(Long channelId, Long userId, Role role, Long addedBy) {
        this.channelId = channelId;
        this.userId = userId;
        this.role = role;
        this.addedBy = addedBy;
        this.addedAt = Instant.now();
    }
}
