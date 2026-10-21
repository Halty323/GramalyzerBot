package com.halty.gramalyzerbot.post;

import com.halty.gramalyzerbot.channel.*;
import com.halty.gramalyzerbot.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/** Gives every person a sandbox channel full of sample data to explore before connecting a real one. */
@Service
@RequiredArgsConstructor
public class DemoService {

    private static final long DEMO_BASE = -4_000_000_000_000_000_000L;
    private static final int DAYS = 240;

    private final ChannelService channels;
    private final MembershipService membership;
    private final PostRepository posts;
    private final SubscriberSnapshotRepository snapshots;

    @Transactional
    public Channel createFor(User user) {
        long chatId = DEMO_BASE - user.getTelegramId();
        Optional<Channel> existing = channels.byChatId(chatId);
        if (existing.isPresent()) {
            membership.add(existing.get(), user, Role.OWNER, null);
            return existing.get();
        }
        Channel ch = channels.register(chatId, "🌌 Demo channel", "demo_channel", 12_480);
        ch.setDemo(true);
        ch.setTimezone("Europe/Berlin");
        ch.setTimezoneConfirmed(true);
        ch.setHistoryImported(true);
        ch = channels.save(ch);
        membership.add(ch, user, Role.OWNER, null);

        populate(ch, user.getTelegramId(), 12_480);
        return ch;
    }

    /**
     * Fills a channel with {@value #DAYS} days of realistic fake posts and a subscriber history that ends at
     * {@code subscribers}. Used for the bot's own sandbox channel and for the CRM's demo channels.
     */
    @Transactional
    public void populate(Channel ch, long seed, int subscribers) {
        populate(ch, seed, subscribers, DemoGenerator.Theme.TECH);
    }

    /** Like {@link #populate(Channel, long, int)}, with posts about the given subject. */
    @Transactional
    public void populate(Channel ch, long seed, int subscribers, DemoGenerator.Theme theme) {
        Instant now = Instant.now();
        List<Post> rows = new ArrayList<>();
        int id = 1;
        for (DemoGenerator.DemoPost d : DemoGenerator.generate(now, ch.zone(), seed, DAYS, theme)) {
            Post p = new Post();
            p.setChannelId(ch.getId());
            p.setMessageId(id++);
            p.setPostedAt(d.postedAt());
            p.setKind(d.kind());
            p.setBody(d.text());
            p.setHasLink(d.hasLink());
            p.setReactions(d.reactions());
            p.setReactionBreakdown(d.breakdown());
            p.setComments(d.comments());
            p.setSource(PostSource.DEMO);
            rows.add(p);
        }
        posts.saveAll(rows);

        Random rng = new Random(seed);
        int start = (int) Math.round(subscribers * 0.945);
        List<SubscriberSnapshot> snaps = new ArrayList<>();
        for (int i = 0; i <= 90; i++) {
            int count = start + (int) Math.round((subscribers - start) * i / 90.0) + rng.nextInt(25);
            snaps.add(new SubscriberSnapshot(ch.getId(), now.minus(Duration.ofDays(90 - i)), count));
        }
        snapshots.saveAll(snaps);
    }
}
