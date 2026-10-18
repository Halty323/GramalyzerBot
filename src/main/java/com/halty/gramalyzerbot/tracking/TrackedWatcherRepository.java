package com.halty.gramalyzerbot.tracking;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TrackedWatcherRepository extends JpaRepository<TrackedWatcher, Long> {

    List<TrackedWatcher> findAllByOwnerOrderByCreatedAtAsc(String owner);

    Optional<TrackedWatcher> findByTrackedChannelIdAndOwner(Long channelId, String owner);

    long countByOwner(String owner);

    long countByTrackedChannelId(Long channelId);
}
