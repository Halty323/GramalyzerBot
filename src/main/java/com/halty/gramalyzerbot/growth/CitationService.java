package com.halty.gramalyzerbot.growth;

import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.channel.Links;
import com.halty.gramalyzerbot.tracking.TrackedChannel;
import com.halty.gramalyzerbot.tracking.TrackedChannelRepository;
import com.halty.gramalyzerbot.tracking.TrackedPost;
import com.halty.gramalyzerbot.tracking.TrackedPostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Which other channels forwarded or mentioned an own channel lately, among every public channel Gramalyzer reads.
 * <p>
 * Most new subscribers come from other channels: Telegram's own statistics sort new followers by exactly that, and
 * Similar Channels are built from shared audiences. Forwards and mentions (TGStat calls them citations) are the
 * organic part of that, and the only part a bot can see from outside. Only channels someone watches are read, so this
 * is a floor, not a count of everything.
 */
@Service
@RequiredArgsConstructor
public class CitationService {

    static final Duration LOOK_BACK = Duration.ofDays(60);
    static final int SHOWN = 10;

    private final TrackedPostRepository posts;
    private final TrackedChannelRepository tracked;

    /**
     * @param forwards posts that forwarded the channel; @param mentions posts that named its @username or link
     * @param url      the latest such post
     */
    public record Citation(String username, String title, Integer subscribers, int forwards, int mentions, Instant last, String url) {}

    public List<Citation> citations(Channel ch) {
        if (ch.getUsername() == null || ch.getUsername().isBlank() || ch.isDemo()) return List.of();
        String username = ch.getUsername().toLowerCase(Locale.ROOT).replaceFirst("^@", "");
        String title = ch.getTitle() == null ? "\u0000" : ch.getTitle().toLowerCase(Locale.ROOT).strip();
        List<TrackedPost> found = posts.findCiting(title, username, Instant.now().minus(LOOK_BACK));
        if (found.isEmpty()) return List.of();
        Map<Long, TrackedChannel> channels = tracked.findAllById(found.stream().map(TrackedPost::getTrackedChannelId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(TrackedChannel::getId, c -> c));
        Map<Long, List<TrackedPost>> byChannel = found.stream().collect(Collectors.groupingBy(TrackedPost::getTrackedChannelId));
        List<Citation> out = new ArrayList<>();
        byChannel.forEach((id, list) -> {
            TrackedChannel c = channels.get(id);
            if (c == null || c.getUsername().equalsIgnoreCase(username)) return; // the channel quoting itself
            int forwards = (int) list.stream().filter(p -> p.getForwardedFrom() != null && p.getForwardedFrom().strip().equalsIgnoreCase(title)).count();
            TrackedPost last = list.stream().max(Comparator.comparing(TrackedPost::getPostedAt)).orElseThrow();
            out.add(new Citation(c.getUsername(), c.getTitle(), c.getSubscribers(), forwards, list.size() - forwards, last.getPostedAt(),
                    Links.post(0, c.getUsername(), c.isDemo(), last.getMessageId())));
        });
        out.sort(Comparator.comparingInt((Citation c) -> c.forwards() + c.mentions()).reversed().thenComparing(Citation::last, Comparator.reverseOrder()));
        return out.size() > SHOWN ? out.subList(0, SHOWN) : out;
    }
}
