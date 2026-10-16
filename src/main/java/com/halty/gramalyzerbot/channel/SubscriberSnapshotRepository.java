package com.halty.gramalyzerbot.channel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SubscriberSnapshotRepository extends JpaRepository<SubscriberSnapshot, Long> {

    List<SubscriberSnapshot> findAllByChannelIdAndTakenAtAfterOrderByTakenAtAsc(Long channelId, Instant after);

    Optional<SubscriberSnapshot> findFirstByChannelIdOrderByTakenAtDesc(Long channelId);

    void deleteAllByChannelId(Long channelId);
}
