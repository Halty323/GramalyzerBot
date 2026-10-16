package com.halty.gramalyzerbot.channel;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Records who changed what on a channel so teams can see the history. */
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditRepository repo;

    @Transactional
    public void record(Long channelId, Long actorUserId, String action, String details) {
        AuditEntry e = new AuditEntry();
        e.setChannelId(channelId);
        e.setActorUserId(actorUserId);
        e.setAction(action);
        e.setDetails(details == null ? null : (details.length() > 250 ? details.substring(0, 250) : details));
        e.setCreatedAt(Instant.now());
        repo.save(e);
    }

    public List<AuditEntry> recent(Long channelId) { return repo.findTop10ByChannelIdOrderByCreatedAtDesc(channelId); }
}
