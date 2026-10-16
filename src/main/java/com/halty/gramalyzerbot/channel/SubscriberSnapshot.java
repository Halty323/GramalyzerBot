package com.halty.gramalyzerbot.channel;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "subscriber_snapshots")
@Getter
@Setter
@NoArgsConstructor
public class SubscriberSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @Column(name = "taken_at", nullable = false)
    private Instant takenAt;

    @Column(name = "member_count", nullable = false)
    private int memberCount;

    public SubscriberSnapshot(Long channelId, Instant takenAt, int memberCount) {
        this.channelId = channelId;
        this.takenAt = takenAt;
        this.memberCount = memberCount;
    }
}
