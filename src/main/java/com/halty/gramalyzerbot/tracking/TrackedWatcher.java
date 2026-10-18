package com.halty.gramalyzerbot.tracking;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Someone keeping an eye on a tracked channel; see {@link TrackingService#WEB} and {@link TrackingService#owner}. */
@Entity
@Table(name = "tracked_watchers")
@Getter
@Setter
@NoArgsConstructor
public class TrackedWatcher {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tracked_channel_id", nullable = false)
    private Long trackedChannelId;

    @Column(nullable = false)
    private String owner;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public TrackedWatcher(Long trackedChannelId, String owner) {
        this.trackedChannelId = trackedChannelId;
        this.owner = owner;
        this.createdAt = Instant.now();
    }
}
