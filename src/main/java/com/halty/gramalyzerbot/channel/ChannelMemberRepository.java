package com.halty.gramalyzerbot.channel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChannelMemberRepository extends JpaRepository<ChannelMember, Long> {

    Optional<ChannelMember> findByChannelIdAndUserId(Long channelId, Long userId);

    List<ChannelMember> findAllByChannelId(Long channelId);

    List<ChannelMember> findAllByUserId(Long userId);

    List<ChannelMember> findAllByChannelIdAndNotifyDigestTrue(Long channelId);

    List<ChannelMember> findAllByChannelIdAndNotifyNudgesTrue(Long channelId);

    void deleteAllByChannelId(Long channelId);
}
