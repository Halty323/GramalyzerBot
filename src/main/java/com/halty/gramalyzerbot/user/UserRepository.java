package com.halty.gramalyzerbot.user;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByTelegramId(Long telegramId);

    List<User> findAllByBotAdminTrue();

    long countByBannedTrue();

    Page<User> findAllByOrderByLastSeenAtDesc(Pageable pageable);
}
