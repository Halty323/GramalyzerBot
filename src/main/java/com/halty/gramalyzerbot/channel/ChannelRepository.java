package com.halty.gramalyzerbot.channel;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChannelRepository extends JpaRepository<Channel, Long> {

    Optional<Channel> findByTelegramChatId(Long telegramChatId);

    Optional<Channel> findByWorkspaceChannelId(String workspaceChannelId);

    List<Channel> findAllByDemoFalseAndActiveTrue();

    Page<Channel> findAllByOrderByIdDesc(Pageable pageable);

    long countByDemoFalse();
}
