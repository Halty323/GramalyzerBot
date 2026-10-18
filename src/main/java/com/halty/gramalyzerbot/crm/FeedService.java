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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The messages of one of the workspace's own channels, for the website's channel viewer. The best source wins:
 * <ol>
 *   <li>{@code preview}: a public channel's web preview, the only source with view counts;</li>
 *   <li>{@code bot}: the posts the bot collected (reactions and comments, no views);</li>
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
                TrackedChannel pub = tracking.lookup(handle);
                List<FeedPost> feed = tracking.posts(pub).stream().map(p -> FeedPost.of(pub, p))
                        .sorted(Comparator.comparing(FeedPost::postedAt).reversed()).toList();
                if (!feed.isEmpty()) return new Feed(crm.id(), "preview", feed, scheduled, tracking.stats(pub, zone));
            } catch (TrackingException e) {
                log.debug("No public preview for {}: {}", handle, e.code());
            }
        }

        Optional<Channel> analytics = bridge.analyticsFor(crm);
        if (analytics.isEmpty()) analytics = demoData.ensure(crm);
        if (analytics.isPresent()) {
            Channel ch = analytics.get();
            boolean comments = ch.isDemo() || ch.getDiscussionChatId() != null;
            List<FeedPost> feed = posts.recent(ch, 0, POSTS).stream().map(p -> fromBot(ch, p, comments)).toList();
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

    private FeedPost fromBot(Channel ch, Post p, boolean comments) {
        // The demo's sample posts only have reactions; give them believable views so the viewer looks like the real thing.
        Integer views = ch.isDemo() ? p.getReactions() * (18 + Math.floorMod(p.getMessageId() * 7, 13)) + 40 : null;
        return new FeedPost("bot:" + p.getId(), p.getMessageId(), p.getPostedAt(), p.getKind().name(), p.getBody() == null ? "" : p.getBody(),
                views, p.getReactions(), FeedPost.breakdown(p.getReactionBreakdown()), comments ? p.getComments() : null, null,
                Links.post(ch.getTelegramChatId(), ch.getUsername(), ch.isDemo(), p.getMessageId()), null, p.getEditedAt() != null, "published");
    }
}
