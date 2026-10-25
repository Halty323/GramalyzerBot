package com.halty.gramalyzerbot.crm;

import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.channel.Links;
import com.halty.gramalyzerbot.post.Post;
import com.halty.gramalyzerbot.post.PostService;
import com.halty.gramalyzerbot.tracking.FeedPost;
import com.halty.gramalyzerbot.tracking.TrackedChannel;
import com.halty.gramalyzerbot.tracking.TrackedStats;
import com.halty.gramalyzerbot.tracking.TrackingException;
import com.halty.gramalyzerbot.tracking.TrackingService;
import com.halty.gramalyzerbot.workspace.Models;
import com.halty.gramalyzerbot.workspace.WorkspaceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The messages of one of the workspace's own channels, for the website's channel viewer. The best source wins:
 * <ol>
 *   <li>{@code preview}: a public channel's web preview, the only source with view counts, stored as it is read
 *       and refreshed when two minutes old; posts the bot received since then come first;</li>
 *   <li>{@code bot}: the posts the bot collected (reactions and comments, no views), an album as one post;</li>
 *   <li>{@code workspace}: the posts the website published or saw.</li>
 * </ol>
 * Scheduled posts from the queue are always included, like Telegram's own scheduled messages.
 */
@Service
@Slf4j
public class FeedService {

    private static final int POSTS = 60;

    private final WorkspaceService workspace;
    private final CrmBridge bridge;
    private final CrmDemoData demoData;
    private final PostService posts;
    private final TrackingService tracking;
    private final boolean demo;

    public FeedService(WorkspaceService workspace, CrmBridge bridge, CrmDemoData demoData, PostService posts,
                       TrackingService tracking, @Value("${gramalyzer.demo:false}") boolean demo) {
        this.workspace = workspace;
        this.bridge = bridge;
        this.demoData = demoData;
        this.posts = posts;
        this.tracking = tracking;
        this.demo = demo;
    }

    /** @param stats only for the {@code preview} source: the public channel's statistics */
    public record Feed(String channelId, String source, List<FeedPost> posts, List<FeedPost> scheduled, TrackedStats stats) {}

    /** @throws WorkspaceService.Problem (404) when the CRM has no such channel */
    public Feed forChannel(String channelId, ZoneId zone) {
        Models.Channel crm = workspace.channel(channelId);
        List<Models.Post> all = workspace.workspace().posts().stream().filter(p -> p.channelId().equals(crm.id())).toList();
        List<FeedPost> scheduled = all.stream().filter(p -> "scheduled".equals(p.status()))
                .sorted(Comparator.comparing(Models.Post::scheduledAt))
                .map(p -> new FeedPost(p.id(), null, p.scheduledAt(), "TEXT", p.body(), null, 0, List.of(), null, null, null, null, false, "scheduled"))
                .toList();

        String handle = crm.handle() == null ? "" : crm.handle().replaceFirst("^@", "");
        if (!demo && !handle.isBlank()) {
            try {
                TrackedChannel pub = tracking.own(crm.id(), handle);
                List<FeedPost> feed = new ArrayList<>(tracking.posts(pub).stream().map(p -> FeedPost.of(pub, p)).toList());
                if (!feed.isEmpty()) {
                    // The bot hears about a post the moment it is published; the public page shows it after the next refresh.
                    int newest = feed.stream().mapToInt(FeedPost::messageId).max().orElse(0);
                    analytics(crm).ifPresent(ch -> feed.addAll(fromBot(ch, newest)));
                    feed.sort(Comparator.comparing(FeedPost::postedAt).thenComparing(FeedPost::messageId).reversed());
                    return new Feed(crm.id(), "preview", feed.subList(0, Math.min(POSTS, feed.size())), scheduled, tracking.stats(pub, zone));
                }
            } catch (TrackingException e) {
                log.debug("No public preview for {}: {}", handle, e.code());
            }
        }

        Optional<Channel> analytics = analytics(crm);
        if (analytics.isPresent()) {
            List<FeedPost> feed = fromBot(analytics.get(), 0);
            if (!feed.isEmpty()) return new Feed(crm.id(), "bot", feed, scheduled, null);
        }

        List<FeedPost> feed = all.stream().filter(p -> "published".equals(p.status()))
                .sorted(Comparator.comparing(Models.Post::scheduledAt).reversed())
                .map(p -> new FeedPost(p.id(), p.telegramMessageId() == null ? null : p.telegramMessageId().intValue(), p.scheduledAt(), "TEXT",
                        p.body(), demo ? (Integer) (int) p.views() : null, (int) p.reactions(), List.of(), null, null,
                        p.telegramMessageId() == null || handle.isBlank() || demo ? null : "https://t.me/" + handle + "/" + p.telegramMessageId(),
                        null, false, "published"))
                .toList();
        return new Feed(crm.id(), "workspace", feed, scheduled, null);
    }

    private Optional<Channel> analytics(Models.Channel crm) {
        Optional<Channel> analytics = bridge.analyticsFor(crm);
        return analytics.isPresent() ? analytics : demoData.ensure(crm);
    }

    /**
     * The bot's latest posts with a message id above {@code after}, newest first. Telegram sends an album as one message
     * per picture or video, and only one of them carries the caption; here, as in the channel, it is one post.
     */
    private List<FeedPost> fromBot(Channel ch, int after) {
        boolean comments = ch.isDemo() || ch.getDiscussionChatId() != null;
        // an album of up to ten items takes up to ten rows
        List<Post> recent = posts.recent(ch, 0, POSTS * 3);
        Map<String, List<Post>> albums = new LinkedHashMap<>();
        for (Post p : recent) if (p.getMediaGroupId() != null) albums.computeIfAbsent(p.getMediaGroupId(), k -> new ArrayList<>()).add(p);
        List<FeedPost> out = new ArrayList<>();
        for (Post p : recent) {
            if (out.size() >= POSTS) break;
            List<Post> album = p.getMediaGroupId() == null ? List.of(p) : albums.remove(p.getMediaGroupId());
            if (album == null) continue; // an item of an album already added
            Post first = album.stream().min(Comparator.comparing(Post::getMessageId)).orElseThrow();
            if (first.getMessageId() > after) out.add(fromBot(ch, album, first, comments));
        }
        return out;
    }

    private FeedPost fromBot(Channel ch, List<Post> items, Post first, boolean comments) {
        String text = items.stream().sorted(Comparator.comparing(Post::getMessageId)).map(Post::getBody)
                .filter(b -> b != null && !b.isBlank()).findFirst().orElse("");
        int reactions = items.stream().mapToInt(Post::getReactions).sum();
        Map<String, Integer> byEmoji = new LinkedHashMap<>();
        for (Post p : items)
            for (FeedPost.Reaction r : FeedPost.breakdown(p.getReactionBreakdown())) byEmoji.merge(r.emoji(), r.count(), Integer::sum);
        List<FeedPost.Reaction> list = new ArrayList<>(byEmoji.entrySet().stream().map(e -> new FeedPost.Reaction(e.getKey(), e.getValue())).toList());
        list.sort((a, b) -> Integer.compare(b.count(), a.count()));
        String kind = items.size() > 1 ? "ALBUM" : first.getKind().name();
        // The demo's sample posts only have reactions; give them believable views so the viewer looks like the real thing.
        Integer views = ch.isDemo() ? reactions * (18 + Math.floorMod(first.getMessageId() * 7, 13)) + 40 : null;
        return new FeedPost("bot:" + first.getId(), first.getMessageId(), first.getPostedAt(), kind, text,
                views, reactions, list, comments ? items.stream().mapToInt(Post::getComments).sum() : null, null,
                Links.post(ch.getTelegramChatId(), ch.getUsername(), ch.isDemo(), first.getMessageId()), null,
                items.stream().anyMatch(p -> p.getEditedAt() != null), "published");
    }
}
