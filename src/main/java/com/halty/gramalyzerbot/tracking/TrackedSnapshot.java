package com.halty.gramalyzerbot.tracking;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/** A tracked channel's size and reach on one UTC day. */
@Entity
@Table(name = "tracked_snapshots")
@Getter
@Setter
@NoArgsConstructor
public class TrackedSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tracked_channel_id", nullable = false)
    private Long trackedChannelId;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate day;

    private Integer subscribers;

    @Column(name = "avg_views")
    private Integer avgViews;

    public TrackedSnapshot(Long trackedChannelId, LocalDate day) {
        this.trackedChannelId = trackedChannelId;
        this.day = day;
    }
}
