package com.halty.gramalyzerbot.tracking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TrackedPostRepository extends JpaRepository<TrackedPost, Long> {

    List<TrackedPost> findAllByTrackedChannelIdAndMessageIdIn(Long channelId, Collection<Integer> messageIds);

    List<TrackedPost> findAllByTrackedChannelIdAndPostedAtAfterOrderByPostedAtDesc(Long channelId, Instant after);

    List<TrackedPost> findTop60ByTrackedChannelIdOrderByMessageIdDesc(Long channelId);

    Optional<TrackedPost> findFirstByTrackedChannelIdOrderByMessageIdDesc(Long channelId);

    List<TrackedPost> findAllByTrackedChannelIdAndMessageIdBetween(Long channelId, Integer from, Integer to);

    @Modifying
    @Query("delete from TrackedPost p where p.trackedChannelId = :channelId")
    int deleteAllByChannel(@Param("channelId") Long channelId);
}
