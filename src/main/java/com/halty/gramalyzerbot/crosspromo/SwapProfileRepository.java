package com.halty.gramalyzerbot.crosspromo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SwapProfileRepository extends JpaRepository<SwapProfile, Long> {

    List<SwapProfile> findAllByEnabledTrueAndLanguage(String language);

    void deleteAllByChannelId(Long channelId);
}
