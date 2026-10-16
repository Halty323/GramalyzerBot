package com.halty.gramalyzerbot.post;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DiscussionRootRepository extends JpaRepository<DiscussionRoot, Long> {

    Optional<DiscussionRoot> findByGroupChatIdAndGroupMessageId(Long groupChatId, Integer groupMessageId);

    void deleteAllByChannelId(Long channelId);
}
