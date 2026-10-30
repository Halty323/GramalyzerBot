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

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final Map<Long, Known> descriptions = new ConcurrentHashMap<>();

    public SearchCheck.Result check(Channel ch) {
        return SearchCheck.check(ch.getTitle(), ch.getUsername(), description(ch), texts(ch));
    }

    /** What the website knows about the channel; the bot's posts, when it's in the channel, give the words. */
    public SearchCheck.Result check(Models.Channel crm, Optional<Channel> ch) {
        String handle = crm.handle() == null || crm.handle().isBlank() ? ch.map(Channel::getUsername).orElse(null) : crm.handle();
        String description = crm.description() == null || crm.description().isBlank() ? ch.map(this::description).orElse(null) : crm.description();
        return SearchCheck.check(crm.name(), handle, description, ch.map(this::texts).orElse(List.of()));
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
