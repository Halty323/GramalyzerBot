package com.halty.gramalyzerbot.post;

import com.halty.gramalyzerbot.analytics.PostKind;
import com.halty.gramalyzerbot.analytics.TextFeatures;
import com.halty.gramalyzerbot.bot.event.Events;
import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.channel.ChannelService;
import com.halty.gramalyzerbot.common.UserFacingException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/** Collects posts, reactions and comments as they happen, and lets moderators curate them. */
@Service
@RequiredArgsConstructor
public class PostService {

    private final PostRepository posts;
    private final DiscussionRootRepository roots;
    private final ChannelService channels;
    private final AnalysisService analysis;

    // ---------------------------------------------------------------- live collection

    @Transactional
    public void onChannelPost(Events.ChannelPost e) {
        Optional<Channel> found = channels.byChatId(e.chatId());
        if (found.isEmpty()) return;
        Channel ch = found.get();
        Post p = posts.findByChannelIdAndMessageId(ch.getId(), e.messageId()).orElse(null);
        if (p == null) {
            p = new Post();
            p.setChannelId(ch.getId());
            p.setMessageId(e.messageId());
            p.setPostedAt(e.date());
            p.setSource(PostSource.LIVE);
            if (!e.edited()) {
                ch.setLastPostEventAt(Instant.now());
                channels.save(ch);
            }
        } else if (e.edited()) {
            p.setEditedAt(Instant.now());
        } else {
            return; // duplicate delivery of a post we already have
        }
        p.setKind(e.kind());
        p.setBody(e.text());
        p.setHasLink(e.hasLink());
        if (e.mediaGroupId() != null) p.setMediaGroupId(e.mediaGroupId());
        posts.save(p);
    }

    /**
     * A post the CRM queue published. Telegram does not send a bot the channel updates for its own messages,
     * so without this call such posts would never reach the analysis.
     */
    @Transactional
    public void recordPublished(long chatId, int messageId, String text, Instant at) {
        channels.byChatId(chatId).ifPresent(ch -> {
            if (posts.findByChannelIdAndMessageId(ch.getId(), messageId).isPresent()) return; // the update arrived first
            Post p = new Post();
            p.setChannelId(ch.getId());
            p.setMessageId(messageId);
            p.setPostedAt(at);
            p.setSource(PostSource.LIVE);
            p.setKind(PostKind.TEXT); // the CRM queue publishes plain text only
            p.setBody(text);
            p.setHasLink(TextFeatures.hasLink(text));
            posts.save(p);
            ch.setLastPostEventAt(Instant.now());
            channels.save(ch);
        });
    }

    @Transactional
    public void onReactions(Events.Reactions e) {
        channels.byChatId(e.chatId()).ifPresent(ch -> {
            ch.setLastReactionEventAt(Instant.now());
            channels.save(ch);
            posts.findByChannelIdAndMessageId(ch.getId(), e.messageId()).ifPresent(p -> {
                p.setReactions(e.total());
                p.setReactionBreakdown(e.breakdown());
                posts.save(p);
            });
        });
    }

    /**
     * Messages in a group matter only if they belong to the discussion thread of a channel post:
     * the auto-forwarded copy of the post becomes the thread root, replies are comments.
     */
    @Transactional
    public void onGroupMessage(Events.GroupMessage e) {
        if (e.automaticForward() && e.originChatId() != null && e.originMessageId() != null) {
            channels.byChatId(e.originChatId()).ifPresent(ch -> {
                if (roots.findByGroupChatIdAndGroupMessageId(e.chatId(), e.messageId()).isEmpty()) {
                    DiscussionRoot r = new DiscussionRoot();
                    r.setGroupChatId(e.chatId());
                    r.setGroupMessageId(e.messageId());
                    r.setChannelId(ch.getId());
                    r.setPostMessageId(e.originMessageId());
                    roots.save(r);
                }
                if (!Objects.equals(ch.getDiscussionChatId(), e.chatId())) {
                    ch.setDiscussionChatId(e.chatId());
                    channels.save(ch);
                }
            });
            return;
        }
        Integer rootId = e.threadId() != null ? e.threadId() : e.replyToId();
        if (rootId == null || rootId == e.messageId()) return;
        roots.findByGroupChatIdAndGroupMessageId(e.chatId(), rootId)
                .ifPresent(r -> posts.incrementComments(r.getChannelId(), r.getPostMessageId()));
    }

    // ---------------------------------------------------------------- import

    /** Inserts imported posts, or merges them into posts we already know. Returns {created, updated}. */
    @Transactional
    public int[] upsertImported(Channel ch, List<ExportParser.ExportedPost> chunk) {
        List<Integer> ids = chunk.stream().map(ExportParser.ExportedPost::messageId).toList();
        Map<Integer, Post> existing = new HashMap<>();
        for (Post p : posts.findAllByChannelIdAndMessageIdIn(ch.getId(), ids)) existing.put(p.getMessageId(), p);
        int created = 0, updated = 0;
        List<Post> toSave = new ArrayList<>();
        for (ExportParser.ExportedPost x : chunk) {
            Post p = existing.get(x.messageId());
            if (p == null) {
                p = new Post();
                p.setChannelId(ch.getId());
                p.setMessageId(x.messageId());
                p.setPostedAt(x.date());
                p.setKind(x.kind());
                p.setBody(x.text());
                p.setHasLink(x.hasLink());
                p.setMediaGroupId(x.mediaGroupId());
                p.setReactions(x.reactions());
                p.setReactionBreakdown(x.breakdown());
                p.setSource(PostSource.IMPORT);
                created++;
            } else {
                updated++;
                if (x.reactions() > p.getReactions()) {
                    p.setReactions(x.reactions());
                    p.setReactionBreakdown(x.breakdown());
                }
                if (p.getBody() == null || p.getBody().isBlank()) p.setBody(x.text());
                if (p.getMediaGroupId() == null) p.setMediaGroupId(x.mediaGroupId());
            }
            toSave.add(p);
        }
        posts.saveAll(toSave);
        return new int[]{created, updated};
    }

    // ---------------------------------------------------------------- moderation

    public Post getFor(Channel ch, long postId) {
        return posts.findById(postId).filter(p -> p.getChannelId().equals(ch.getId()))
                .orElseThrow(() -> new UserFacingException("I can't find that post any more."));
    }

    /** Returns the new "excluded" state. */
    @Transactional
    public boolean toggleExcluded(Channel ch, long postId) {
        Post p = getFor(ch, postId);
        p.setExcluded(!p.isExcluded());
        posts.save(p);
        analysis.invalidate(ch.getId());
        return p.isExcluded();
    }

    /** A blank topic clears the label. */
    @Transactional
    public void setTopic(Channel ch, long postId, String topic) {
        Post p = getFor(ch, postId);
        String clean = topic == null ? "" : topic.trim().replaceFirst("^#", "").toLowerCase(Locale.ROOT);
        if (clean.length() > 40) throw new UserFacingException("Keep topics short (40 characters max).");
        if (!clean.isEmpty() && !clean.matches("[\\p{L}\\p{N}_ -]+"))
            throw new UserFacingException("Topics can use letters, numbers, spaces, '-' and '_'.");
        p.setManualTopic(clean.isEmpty() ? null : clean.replace(' ', '_'));
        posts.save(p);
        analysis.invalidate(ch.getId());
    }

    public List<Post> recent(Channel ch, int page, int size) {
        return posts.findByChannelIdOrderByPostedAtDesc(ch.getId(), PageRequest.of(page, size)).getContent();
    }

    public long count(Channel ch) { return posts.countByChannelId(ch.getId()); }

    public Optional<Post> latest(Channel ch) { return posts.findFirstByChannelIdOrderByPostedAtDesc(ch.getId()); }

    public Optional<Post> find(long postId) { return posts.findById(postId); }

    static List<String> topicsOf(Post p) {
        Set<String> out = new LinkedHashSet<>(TextFeatures.hashtags(p.getBody()));
        if (p.getManualTopic() != null && !p.getManualTopic().isBlank()) out.add(p.getManualTopic());
        return new ArrayList<>(out);
    }
}
