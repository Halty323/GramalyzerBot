package com.halty.gramalyzerbot.post;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PostRepository extends JpaRepository<Post, Long> {

    Optional<Post> findByChannelIdAndMessageId(Long channelId, Integer messageId);

    List<Post> findAllByChannelId(Long channelId);

    List<Post> findAllByChannelIdAndMessageIdIn(Long channelId, Collection<Integer> messageIds);

    Page<Post> findByChannelIdOrderByPostedAtDesc(Long channelId, Pageable pageable);

    Optional<Post> findFirstByChannelIdOrderByPostedAtDesc(Long channelId);

    long countByChannelId(Long channelId);

    long countByChannelIdAndPostedAtAfter(Long channelId, Instant after);

    @Modifying
    @Query("update Post p set p.comments = p.comments + 1 where p.channelId = :channelId and p.messageId = :messageId")
    int incrementComments(@Param("channelId") Long channelId, @Param("messageId") Integer messageId);

    @Modifying
    @Query("delete from Post p where p.channelId = :channelId")
    int deleteAllByChannel(@Param("channelId") Long channelId);
}
