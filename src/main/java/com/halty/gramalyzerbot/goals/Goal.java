package com.halty.gramalyzerbot.goals;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/** A channel's subscriber goal: {@link #target} subscribers by {@link #deadline}. */
@Entity
@Table(name = "channel_goals")
@Getter
@Setter
@NoArgsConstructor
public class Goal {

    @Id
    @Column(name = "channel_id")
    private Long channelId;

    @Column(nullable = false)
    private int target;

    @Column(nullable = false)
    private LocalDate deadline;

    /** Subscribers when the goal was set. */
    @Column(name = "start_count", nullable = false)
    private int startCount;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "reached_at")
    private Instant reachedAt;

    @Column(name = "set_by")
    private Long setBy;
}
