package com.halty.gramalyzerbot.tracking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TrackedChannelRepository extends JpaRepository<TrackedChannel, Long> {

    Optional<TrackedChannel> findByUsername(String username);

    @Query("select c from TrackedChannel c where exists (select w from TrackedWatcher w where w.trackedChannelId = c.id)")
    List<TrackedChannel> findAllWatched();

    @Query("select c from TrackedChannel c where c.createdAt < :before and not exists (select w from TrackedWatcher w where w.trackedChannelId = c.id)")
    List<TrackedChannel> findUnwatchedCreatedBefore(@Param("before") Instant before);
}
