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

    /** Posts since {@code after} that forward a channel (by its shown name) or mention its username or link; pass lower case. */
    @Query("select p from TrackedPost p where p.postedAt > :after and (lower(p.forwardedFrom) = :title"
            + " or lower(p.body) like concat('%@', :username, '%') or lower(p.body) like concat('%t.me/', :username, '%'))")
    List<TrackedPost> findCiting(@Param("title") String title, @Param("username") String username, @Param("after") Instant after);

    @Modifying
    @Query("delete from TrackedPost p where p.trackedChannelId = :channelId")
    int deleteAllByChannel(@Param("channelId") Long channelId);
}
