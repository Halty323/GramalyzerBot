package com.halty.gramalyzerbot.seo;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Where an own channel showed up when its owner searched Telegram for a word, as they noted it. */
@Entity
@Table(name = "search_positions")
@Getter
@Setter
@NoArgsConstructor
public class SearchPosition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @Column(nullable = false, length = 64)
    private String word;

    /** 1 = the first result; null = not among the results. */
    private Integer position;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    public SearchPosition(Long channelId, String word, Integer position, Instant checkedAt) {
        this.channelId = channelId;
        this.word = word;
        this.position = position;
        this.checkedAt = checkedAt;
    }
}
