package com.halty.gramalyzerbot.seo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SearchPositionRepository extends JpaRepository<SearchPosition, Long> {

    List<SearchPosition> findAllByChannelIdOrderByCheckedAtDesc(Long channelId);

    long countByChannelId(Long channelId);

    java.util.Optional<SearchPosition> findFirstByChannelIdOrderByCheckedAtAsc(Long channelId);
}
