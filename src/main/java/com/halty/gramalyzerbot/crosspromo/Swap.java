package com.halty.gramalyzerbot.crosspromo;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One channel asked another to swap posts. Once accepted, {@code fromLink} is a promo link in the asking channel for the
 * partner to post, and {@code toLink} one in the partner's channel for the asking channel to post.
 */
@Entity
@Table(name = "swaps")
@Getter
@Setter
@NoArgsConstructor
public class Swap {

    public enum Status { REQUESTED, ACTIVE, DECLINED, CANCELLED, DONE;
        public boolean open() { return this == REQUESTED || this == ACTIVE; }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "from_channel", nullable = false)
    private Long fromChannel;

    @Column(name = "to_channel", nullable = false)
    private Long toChannel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.REQUESTED;

    @Column(name = "from_link")
    private Long fromLink;

    @Column(name = "to_link")
    private Long toLink;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    public boolean involves(long channelId) { return fromChannel == channelId || toChannel == channelId; }

    public long partnerOf(long channelId) { return fromChannel == channelId ? toChannel : fromChannel; }
}
