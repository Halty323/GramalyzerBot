package com.halty.gramalyzerbot.growth;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Someone joined or left an own channel. {@code member} is a hash of who it was, so leaves can be matched to joins. */
@Entity
@Table(name = "member_events")
@Getter
@Setter
@NoArgsConstructor
public class MemberEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @Column(nullable = false)
    private Instant at;

    @Column(nullable = false)
    private boolean joined;

    @Column(nullable = false)
    private long member;

    /** The promo link the person joined through; only set on joins. */
    @Column(name = "promo_link_id")
    private Long promoLinkId;

    public MemberEvent(Long channelId, Instant at, boolean joined, long member, Long promoLinkId) {
        this.channelId = channelId;
        this.at = at;
        this.joined = joined;
        this.member = member;
        this.promoLinkId = promoLinkId;
    }
}
