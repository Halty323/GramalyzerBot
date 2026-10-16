package com.halty.gramalyzerbot.channel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditRepository extends JpaRepository<AuditEntry, Long> {

    List<AuditEntry> findTop10ByChannelIdOrderByCreatedAtDesc(Long channelId);

    void deleteAllByChannelId(Long channelId);
}
