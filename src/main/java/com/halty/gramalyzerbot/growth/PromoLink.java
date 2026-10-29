package com.halty.gramalyzerbot.growth;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** An invite link the bot made for one promo, so the people who join through it can be counted. */
@Entity
@Table(name = "promo_links")
@Getter
@Setter
@NoArgsConstructor
public class PromoLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String url;

    /** What the promo cost, in {@link #currency}; null when free or not told. */
    private Double cost;

    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;
}
