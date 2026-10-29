package com.halty.gramalyzerbot.growth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MemberEventRepository extends JpaRepository<MemberEvent, Long> {

    List<MemberEvent> findAllByChannelIdAndAtAfterOrderByAtAsc(Long channelId, Instant after);

    List<MemberEvent> findAllByChannelIdAndPromoLinkIdIn(Long channelId, Collection<Long> linkIds);

    List<MemberEvent> findAllByChannelIdAndMemberInOrderByAtAsc(Long channelId, Collection<Long> members);

    Optional<MemberEvent> findFirstByChannelIdOrderByAtAsc(Long channelId);

    long countByChannelIdAndJoinedAndAtAfter(Long channelId, boolean joined, Instant after);

    void deleteAllByChannelId(Long channelId);
}
