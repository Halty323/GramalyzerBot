package com.halty.gramalyzerbot.seo;

import com.halty.gramalyzerbot.analytics.Sample;
import com.halty.gramalyzerbot.bot.BotApi;
import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.post.AnalysisService;
import com.halty.gramalyzerbot.tracking.TrackedChannel;
import com.halty.gramalyzerbot.tracking.TrackingService;
import com.halty.gramalyzerbot.workspace.Models;
import com.halty.gramalyzerbot.workspace.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** {@link SearchCheck} for a bot channel or a website channel, with the description read from Telegram. */
@Service
@RequiredArgsConstructor
public class SearchCheckService {

    private static final int POSTS = 150;
    private static final Duration KEEP_DESCRIPTION = Duration.ofHours(1);

    private record Known(String description, Instant at) {}

    private final AnalysisService analysis;
    private final BotApi bot;
    private final TrackingService tracking;
    private final WorkspaceService workspace;
    private final SearchPositionRepository positions;
    private final Map<Long, Known> descriptions = new ConcurrentHashMap<>();

    static final int MAX_POSITIONS = 500;
    static final int SHOWN_WORDS = 8;

    public SearchCheck.Result check(Channel ch) {
        return SearchCheck.check(ch.getTitle(), ch.getUsername(), description(ch), texts(ch)).withPositions(positions(ch));
    }

    /** The latest noted position for each word, newest first, with the one noted before it. */
    public List<SearchCheck.Position> positions(Channel ch) {
        Map<String, List<SearchPosition>> byWord = new LinkedHashMap<>();
        for (SearchPosition p : positions.findAllByChannelIdOrderByCheckedAtDesc(ch.getId()))
            byWord.computeIfAbsent(p.getWord().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(p);
        return byWord.values().stream().limit(SHOWN_WORDS)
                .map(l -> new SearchCheck.Position(l.get(0).getWord(), l.get(0).getPosition(), l.get(0).getCheckedAt(),
                        l.size() > 1 ? l.get(1).getPosition() : null))
                .toList();
    }

    /** Notes where the channel was when its owner searched for {@code word}; null = not in the results. */
    @Transactional
    public List<SearchCheck.Position> note(Channel ch, String word, Integer position) {
        String w = word == null ? "" : word.strip();
        if (w.isEmpty() || w.codePointCount(0, w.length()) > 64) throw new WorkspaceService.Problem(400, "Give the word you searched for, up to 64 characters.");
        if (position != null && (position < 1 || position > 1000)) throw new WorkspaceService.Problem(400, "A position is 1 or more; leave it empty when the channel wasn't in the results.");
        if (positions.countByChannelId(ch.getId()) >= MAX_POSITIONS) // keep the history bounded: forget the oldest note
            positions.findFirstByChannelIdOrderByCheckedAtAsc(ch.getId()).ifPresent(positions::delete);
        positions.save(new SearchPosition(ch.getId(), w, position, Instant.now()));
        return positions(ch);
    }

    /** What the website knows about the channel; the bot's posts, when it's in the channel, give the words. */
    public SearchCheck.Result check(Models.Channel crm, Optional<Channel> ch) {
        String handle = crm.handle() == null || crm.handle().isBlank() ? ch.map(Channel::getUsername).orElse(null) : crm.handle();
        String description = crm.description() == null || crm.description().isBlank() ? ch.map(this::description).orElse(null) : crm.description();
        SearchCheck.Result r = SearchCheck.check(crm.name(), handle, description, ch.map(this::texts).orElse(List.of()));
        return ch.map(c -> r.withPositions(positions(c))).orElse(r);
    }

    private List<String> texts(Channel ch) {
        return analysis.samples(ch).stream().filter(s -> !s.excluded()).sorted(Comparator.comparing(Sample::postedAt).reversed())
                .limit(POSTS).map(Sample::text).filter(t -> !t.isBlank()).toList();
    }

    /** The channel's "about" text from Telegram, kept for an hour; for a sample channel, its website twin's. */
    String description(Channel ch) {
        if (ch.isDemo()) {
            if (ch.getWorkspaceChannelId() == null) return null;
            try {
                return workspace.channel(ch.getWorkspaceChannelId()).description();
            } catch (WorkspaceService.Problem gone) {
                return null;
            }
        }
        Known known = descriptions.get(ch.getId());
        if (known != null && known.at().isAfter(Instant.now().minus(KEEP_DESCRIPTION))) return known.description();
        String text = bot.chat(ch.getTelegramChatId()).map(BotApi.ChatInfo::description)
                .or(() -> ch.getUsername() == null ? Optional.empty() : tracking.find(ch.getUsername()).map(TrackedChannel::getDescription))
                .orElse(null);
        descriptions.put(ch.getId(), new Known(text, Instant.now()));
        return text;
    }
}
