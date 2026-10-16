package com.halty.gramalyzerbot.post;

import com.halty.gramalyzerbot.analytics.*;
import com.halty.gramalyzerbot.channel.Channel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Loads a channel's posts and runs the analysis engine, with a short cache so screens feel instant. */
@Service
@RequiredArgsConstructor
public class AnalysisService {

    private static final Duration TTL = Duration.ofSeconds(60);

    private final PostRepository posts;
    private final AnalysisEngine engine = new AnalysisEngine();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(Report report, Instant at) {}

    public Report report(Channel ch) { return report(ch, ch.getWindowDays()); }

    public Report report(Channel ch, int windowDays) {
        String key = ch.getId() + "|" + windowDays + "|" + ch.getMetric() + "|" + ch.getTimezone() + "|" + (ch.getDiscussionChatId() != null);
        Cached c = cache.get(key);
        if (c != null && c.at().isAfter(Instant.now().minus(TTL))) return c.report();
        Report r = engine.analyze(samples(ch), Options.of(ch.zone(), ch.getMetric(), windowDays));
        cache.put(key, new Cached(r, Instant.now()));
        return r;
    }

    public List<Sample> samples(Channel ch) {
        boolean linked = ch.getDiscussionChatId() != null;
        List<Sample> list = posts.findAllByChannelId(ch.getId()).stream().map(p -> toSample(p, linked)).toList();
        return Albums.collapse(list);
    }

    public void invalidate(long channelId) { cache.keySet().removeIf(k -> k.startsWith(channelId + "|")); }

    static Sample toSample(Post p, boolean discussionLinked) {
        boolean commentsKnown = p.getSource() == PostSource.DEMO || (discussionLinked && p.getSource() == PostSource.LIVE);
        return new Sample(p.getId(), p.getMessageId(), p.getPostedAt(), p.getKind(), p.getBody(), p.isHasLink(),
                PostService.topicsOf(p), p.getReactions(), p.getComments(), commentsKnown, p.isExcluded(), p.getMediaGroupId());
    }
}
