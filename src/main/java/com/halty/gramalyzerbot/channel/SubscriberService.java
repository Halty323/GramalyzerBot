package com.halty.gramalyzerbot.channel;

import com.halty.gramalyzerbot.bot.BotApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/** Tracks subscriber counts over time (the Bot API only gives the current number, so we keep snapshots). */
@Service
@RequiredArgsConstructor
public class SubscriberService {

    private final SubscriberSnapshotRepository snapshots;
    private final ChannelService channels;
    private final BotApi gateway;

    public record Growth(Integer current, Integer change, int days, List<Integer> series) {}

    /** Reads the live count and stores a snapshot at most every few hours. */
    public Optional<Integer> refresh(Channel ch) {
        Optional<Integer> count = gateway.memberCount(ch.getTelegramChatId());
        count.ifPresent(c -> {
            ch.setSubscribers(c);
            ch.setSubscribersCheckedAt(Instant.now());
            channels.save(ch);
            Optional<SubscriberSnapshot> last = snapshots.findFirstByChannelIdOrderByTakenAtDesc(ch.getId());
            if (last.isEmpty() || last.get().getTakenAt().isBefore(Instant.now().minus(Duration.ofHours(5))))
                snapshots.save(new SubscriberSnapshot(ch.getId(), Instant.now(), c));
        });
        return count;
    }

    public Growth growth(Channel ch, int days) {
        List<SubscriberSnapshot> list = snapshots.findAllByChannelIdAndTakenAtAfterOrderByTakenAtAsc(
                ch.getId(), Instant.now().minus(Duration.ofDays(days)));
        TreeMap<LocalDate, Integer> byDay = new TreeMap<>();
        for (SubscriberSnapshot s : list) byDay.put(s.getTakenAt().atZone(ch.zone()).toLocalDate(), s.getMemberCount());
        Integer change = list.size() >= 2 ? list.get(list.size() - 1).getMemberCount() - list.get(0).getMemberCount() : null;
        Integer current = ch.getSubscribers() != null ? ch.getSubscribers()
                : list.isEmpty() ? null : list.get(list.size() - 1).getMemberCount();
        return new Growth(current, change, days, new ArrayList<>(byDay.values()));
    }
}
