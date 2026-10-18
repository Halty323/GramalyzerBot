package com.halty.gramalyzerbot.tracking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TrackedSnapshotRepository extends JpaRepository<TrackedSnapshot, Long> {

    Optional<TrackedSnapshot> findByTrackedChannelIdAndDay(Long channelId, LocalDate day);

    List<TrackedSnapshot> findAllByTrackedChannelIdAndDayAfterOrderByDayAsc(Long channelId, LocalDate after);

    @Modifying
    @Query("delete from TrackedSnapshot s where s.trackedChannelId = :channelId")
    int deleteAllByChannel(@Param("channelId") Long channelId);
}
