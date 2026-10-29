package com.halty.gramalyzerbot.growth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PromoLinkRepository extends JpaRepository<PromoLink, Long> {

    List<PromoLink> findAllByChannelIdOrderByCreatedAtDesc(Long channelId);

    Optional<PromoLink> findByChannelIdAndUrl(Long channelId, String url);

    long countByChannelId(Long channelId);

    void deleteAllByChannelId(Long channelId);
}
