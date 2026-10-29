package com.halty.gramalyzerbot.crosspromo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface SwapRepository extends JpaRepository<Swap, Long> {

    @Query("select s from Swap s where s.fromChannel = :ch or s.toChannel = :ch order by s.createdAt desc")
    List<Swap> involving(@Param("ch") Long channelId);

    long countByFromChannelAndStatus(Long fromChannel, Swap.Status status);

    @Query("select s from Swap s where ((s.fromChannel = :a and s.toChannel = :b) or (s.fromChannel = :b and s.toChannel = :a)) and s.status in :statuses")
    List<Swap> between(@Param("a") Long a, @Param("b") Long b, @Param("statuses") Collection<Swap.Status> statuses);

    void deleteAllByFromChannel(Long channelId);

    void deleteAllByToChannel(Long channelId);
}
