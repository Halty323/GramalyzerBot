package com.halty.gramalyzerbot.crosspromo;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** A channel's entry in cross-promo matching: what it's about, its language and a line to partners. */
@Entity
@Table(name = "swap_profiles")
@Getter
@Setter
@NoArgsConstructor
public class SwapProfile {

    @Id
    @Column(name = "channel_id")
    private Long channelId;

    @Column(nullable = false)
    private boolean enabled = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Topic topic = Topic.OTHER;

    /** A language code (en, uk, …) or "other". */
    @Column(nullable = false)
    private String language = "en";

    private String pitch;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
