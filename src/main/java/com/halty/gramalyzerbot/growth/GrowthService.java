package com.halty.gramalyzerbot.growth;

import com.halty.gramalyzerbot.bot.BotApi;
import com.halty.gramalyzerbot.bot.event.Events;
import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.channel.ChannelService;
import com.halty.gramalyzerbot.channel.Links;
import com.halty.gramalyzerbot.common.UserFacingException;
import com.halty.gramalyzerbot.post.Post;
import com.halty.gramalyzerbot.post.PostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Who joins and leaves an own channel, after which post, and through which promo link.
 * <p>
 * Telegram tells an admin bot about every subscriber who joins or leaves a channel (the {@code chat_member} update,
 * from the moment the bot asks for it; nothing older). A join through an invite link carries that link, and links the
 * bot made itself arrive in full, so they can be matched to a {@link PromoLink}. People are kept only as a one-way
 * hash, enough to tell that someone who left had joined through a promo.
 */
@Service
@RequiredArgsConstructor
public class GrowthService {

    /** A post gets the joins and leaves until the next post, but never more than this. */
    static final Duration POST_WINDOW = Duration.ofHours(24);
    /** Leaves within this long count towards a wave. */
    static final Duration WAVE_WINDOW = Duration.ofHours(1);
    /** At most one leave alert per channel this often. */
    static final Duration ALERT_GAP = Duration.ofHours(12);
    static final int MAX_LINKS = 50;
    static final int MAX_NAME = 32;

    private final MemberEventRepository events;
    private final PromoLinkRepository links;
    private final PostRepository posts;
    private final ChannelService channels;
    private final BotApi bot;

    public record Day(String date, int joined, int left) {}

    /** What happened while a post was the newest one (joins through promo links are not counted here). */
    public record PostEffect(int messageId, Instant postedAt, String kind, String preview, int joined, int left, double hours, String url) {
        public int net() { return joined - left; }
    }

    /**
     * One promo link, over its whole life. {@code stayed} joined through it and haven't left. {@code kept7} and
     * {@code kept30} are the shares still there 7 and 30 days after joining, of the people who joined that long ago
     * (null until anyone has): bought or uninterested audiences leave within days, which a join count never shows.
     * {@code costPerSubscriber} divides the cost by those who stayed.
     */
    public record LinkStat(long id, String name, String url, Double cost, String currency, Instant createdAt, boolean revoked,
                           int joined, int left, int stayed, Double costPerSubscriber, Double kept7, Double kept30) {}

    /** How many subscribers posts of one format bring on average: organic joins minus leaves while each was the newest. */
    public record FormatEffect(String kind, int posts, double joined, double left) {
        public double net() { return Math.round((joined - left) * 10) / 10.0; }
    }

    /**
     * @param tracking  whether any join or leave has been seen; false right after connecting, or when the bot isn't an admin
     * @param since     the first join or leave seen, i.e. how far back the numbers can go
     * @param fromLinks of {@code joined}, how many came through promo links
     */
    public record Report(boolean tracking, Instant since, int days, int joined, int left, int fromLinks, List<Day> daily,
                         List<PostEffect> gainers, List<PostEffect> losers, List<LinkStat> links, List<FormatEffect> formats) {
        public int net() { return joined - left; }
    }

    /** A wave of people leaving that the owners should hear about. */
    public record Wave(Channel channel, int left, int joined, Post lastPost) {}

    // ------------------------------------------------------------------ events

    /** Stores a join or leave; returns a wave when this leave tips the last hour into one the owners haven't heard about yet. */
    @Transactional
    public Optional<Wave> record(Events.MemberChange e) {
        if (!"channel".equals(e.chatType()) || e.isBot() || !(e.joined() || e.left())) return Optional.empty();
        Optional<Channel> found = channels.byChatId(e.chatId());
        if (found.isEmpty()) return Optional.empty();
        Channel ch = found.get();
        Long linkId = null;
        if (e.joined() && e.inviteLink() != null)
            linkId = links.findByChannelIdAndUrl(ch.getId(), e.inviteLink()).map(PromoLink::getId).orElse(null);
        events.save(new MemberEvent(ch.getId(), e.date(), e.joined(), member(ch.getId(), e.userId()), linkId));
        return e.left() ? wave(ch, e.date()) : Optional.empty();
    }

    private Optional<Wave> wave(Channel ch, Instant now) {
        if (ch.getLastLeaveAlertAt() != null && ch.getLastLeaveAlertAt().isAfter(now.minus(ALERT_GAP))) return Optional.empty();
        Instant from = now.minus(WAVE_WINDOW);
        int left = (int) events.countByChannelIdAndJoinedAndAtAfter(ch.getId(), false, from);
        int joined = (int) events.countByChannelIdAndJoinedAndAtAfter(ch.getId(), true, from);
        if (!isWave(left, joined, ch.getSubscribers())) return Optional.empty();
        ch.setLastLeaveAlertAt(now);
        ch = channels.save(ch);
        Post last = posts.findFirstByChannelIdOrderByPostedAtDesc(ch.getId()).orElse(null);
        return Optional.of(new Wave(ch, left, joined, last));
    }

    /** At least 5 people (or 0.5% of the channel) left within the hour, and far more left than joined. */
    static boolean isWave(int left, int joined, Integer subscribers) {
        int threshold = Math.max(5, subscribers == null ? 0 : (int) Math.ceil(subscribers * 0.005));
        return left >= threshold && left >= 3 * Math.max(joined, 1);
    }

    /** A stable stand-in for a person, different in every channel, from which their Telegram id can't be read back. */
    static long member(long channelId, long userId) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(("gramalyzer:" + channelId + ":" + userId).getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(h).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ promo links

    public List<PromoLink> links(Channel ch) { return links.findAllByChannelIdOrderByCreatedAtDesc(ch.getId()); }

    public PromoLink link(Channel ch, long linkId) {
        return links.findById(linkId).filter(l -> l.getChannelId().equals(ch.getId()))
                .orElseThrow(() -> new UserFacingException("promo.err.gone"));
    }

    /** Asks Telegram for a new invite link named after the promo. */
    @Transactional
    public PromoLink create(Channel ch, String name, Double cost, String currency) {
        name = name == null ? "" : name.strip();
        if (name.isEmpty()) throw new UserFacingException("promo.err.name");
        if (name.codePointCount(0, name.length()) > MAX_NAME) name = name.substring(0, name.offsetByCodePoints(0, MAX_NAME));
        if (cost != null && (cost < 0 || cost > 1e9 || cost.isNaN())) throw new UserFacingException("promo.err.cost");
        if (links.countByChannelId(ch.getId()) >= MAX_LINKS) throw new UserFacingException("promo.err.too_many");
        String url = ch.isDemo()
                ? "https://t.me/+demo" + Long.toString(Math.abs(new Random().nextLong()), 36)
                : bot.createInviteLink(ch.getTelegramChatId(), name).orElseThrow(() -> new UserFacingException("promo.err.rights"));
        PromoLink l = new PromoLink();
        l.setChannelId(ch.getId());
        l.setName(name);
        l.setUrl(url);
        l.setCost(cost == null || cost == 0 ? null : cost);
        l.setCurrency(l.getCost() == null || currency == null || currency.isBlank() ? null : currency.strip().substring(0, Math.min(8, currency.strip().length())));
        l.setCreatedAt(Instant.now());
        return links.save(l);
    }

    /** Stops the link from working in Telegram; the people who joined through it stay counted. */
    @Transactional
    public PromoLink revoke(Channel ch, long linkId) {
        PromoLink l = link(ch, linkId);
        if (l.getRevokedAt() != null) return l;
        if (!ch.isDemo() && !bot.revokeInviteLink(ch.getTelegramChatId(), l.getUrl())) throw new UserFacingException("promo.err.rights");
        l.setRevokedAt(Instant.now());
        return links.save(l);
    }

    // ------------------------------------------------------------------ report

    @Transactional
    public Report report(Channel ch, int days) {
        if (ch.isDemo()) ensureDemo(ch);
        Instant now = Instant.now();
        Instant from = now.minus(Duration.ofDays(days));
        List<MemberEvent> recent = events.findAllByChannelIdAndAtAfterOrderByAtAsc(ch.getId(), from);
        Instant since = events.findFirstByChannelIdOrderByAtAsc(ch.getId()).map(MemberEvent::getAt).orElse(null);

        int joined = 0, left = 0, fromLinks = 0;
        TreeMap<LocalDate, int[]> byDay = new TreeMap<>();
        if (since != null) {
            LocalDate first = (since.isAfter(from) ? since : from).atZone(ch.zone()).toLocalDate();
            for (LocalDate d = first; !d.isAfter(now.atZone(ch.zone()).toLocalDate()); d = d.plusDays(1)) byDay.put(d, new int[2]);
        }
        for (MemberEvent e : recent) {
            int[] counts = byDay.computeIfAbsent(e.getAt().atZone(ch.zone()).toLocalDate(), d -> new int[2]);
            if (e.isJoined()) {
                joined++;
                counts[0]++;
                if (e.getPromoLinkId() != null) fromLinks++;
            } else {
                left++;
                counts[1]++;
            }
        }
        List<Day> daily = byDay.entrySet().stream().map(en -> new Day(en.getKey().toString(), en.getValue()[0], en.getValue()[1])).toList();

        // a post shortly before counting began still gets the joins and leaves seen after that
        Instant postsFrom = since == null ? null : from.isAfter(since.minus(POST_WINDOW)) ? from : since.minus(POST_WINDOW);
        List<PostEffect> effects = since == null ? List.of() : postEffects(ch, recent, postsFrom, now);
        List<PostEffect> gainers = effects.stream().filter(p -> p.net() > 0)
                .sorted(Comparator.comparingInt(PostEffect::net).reversed().thenComparing(PostEffect::postedAt, Comparator.reverseOrder()))
                .limit(5).toList();
        List<PostEffect> losers = effects.stream().filter(p -> p.left() > p.joined() && p.left() >= 2)
                .sorted(Comparator.comparingInt(PostEffect::net).thenComparing(PostEffect::postedAt, Comparator.reverseOrder()))
                .limit(5).toList();
        return new Report(since != null, since, days, joined, left, fromLinks, daily, gainers, losers, linkStats(ch), formats(effects));
    }

    /** Formats with at least three posts, the one bringing the most subscribers first. */
    static List<FormatEffect> formats(List<PostEffect> effects) {
        Map<String, List<PostEffect>> byKind = effects.stream().collect(Collectors.groupingBy(PostEffect::kind));
        return byKind.entrySet().stream().filter(e -> e.getValue().size() >= 3)
                .map(e -> new FormatEffect(e.getKey(), e.getValue().size(),
                        Math.round(e.getValue().stream().mapToInt(PostEffect::joined).average().orElse(0) * 10) / 10.0,
                        Math.round(e.getValue().stream().mapToInt(PostEffect::left).average().orElse(0) * 10) / 10.0))
                .sorted(Comparator.comparingDouble(FormatEffect::net).reversed()).toList();
    }

    /** Each post, from its time until the next post (at most a day), with the organic joins and the leaves in between. */
    List<PostEffect> postEffects(Channel ch, List<MemberEvent> recent, Instant from, Instant now) {
        List<Post> list = new ArrayList<>(posts.findAllByChannelIdAndPostedAtAfterOrderByPostedAtAsc(ch.getId(), from));
        // an album is one post for the reader: keep its first item
        List<Post> shown = new ArrayList<>();
        for (Post p : list) {
            if (p.getMediaGroupId() != null && !shown.isEmpty() && p.getMediaGroupId().equals(shown.get(shown.size() - 1).getMediaGroupId())) continue;
            shown.add(p);
        }
        List<PostEffect> out = new ArrayList<>();
        int cursor = 0;
        for (int i = 0; i < shown.size(); i++) {
            Post p = shown.get(i);
            Instant start = p.getPostedAt();
            Instant end = start.plus(POST_WINDOW);
            if (i + 1 < shown.size() && shown.get(i + 1).getPostedAt().isBefore(end)) end = shown.get(i + 1).getPostedAt();
            if (end.isAfter(now)) end = now;
            while (cursor < recent.size() && recent.get(cursor).getAt().isBefore(start)) cursor++;
            int joined = 0, left = 0;
            for (int j = cursor; j < recent.size() && recent.get(j).getAt().isBefore(end); j++) {
                MemberEvent e = recent.get(j);
                if (!e.isJoined()) left++;
                else if (e.getPromoLinkId() == null) joined++;
            }
            String body = p.getBody() == null ? "" : p.getBody().strip().replaceAll("\\s+", " ");
            String preview = body.codePointCount(0, body.length()) > 90 ? body.substring(0, body.offsetByCodePoints(0, 89)) + "…" : body;
            double hours = Math.round(Duration.between(start, end).toMinutes() / 6.0) / 10.0;
            out.add(new PostEffect(p.getMessageId(), start, p.getKind().name(), preview, joined, left, hours,
                    Links.post(ch.getTelegramChatId(), ch.getUsername(), ch.isDemo(), p.getMessageId())));
        }
        return out;
    }

    /** Every promo link of the channel, newest first, with who joined through it and who stayed. */
    @Transactional
    public List<LinkStat> linkStats(Channel ch) {
        if (ch.isDemo()) ensureDemo(ch);
        List<PromoLink> all = links(ch);
        if (all.isEmpty()) return List.of();
        Map<Long, List<MemberEvent>> joinsByLink = events.findAllByChannelIdAndPromoLinkIdIn(ch.getId(),
                all.stream().map(PromoLink::getId).toList()).stream().collect(Collectors.groupingBy(MemberEvent::getPromoLinkId));
        // who left after joining through a link: their latest event is a leave
        Set<Long> members = joinsByLink.values().stream().flatMap(List::stream).map(MemberEvent::getMember).collect(Collectors.toSet());
        Map<Long, MemberEvent> latest = new HashMap<>();
        Map<Long, List<MemberEvent>> history = new HashMap<>();
        if (!members.isEmpty()) {
            for (MemberEvent e : events.findAllByChannelIdAndMemberInOrderByAtAsc(ch.getId(), members)) {
                latest.put(e.getMember(), e);
                history.computeIfAbsent(e.getMember(), k -> new ArrayList<>()).add(e);
            }
        }
        Instant now = Instant.now();
        List<LinkStat> out = new ArrayList<>();
        for (PromoLink l : all) {
            List<MemberEvent> joins = joinsByLink.getOrDefault(l.getId(), List.of());
            Set<Long> people = joins.stream().map(MemberEvent::getMember).collect(Collectors.toSet());
            int left = (int) people.stream().filter(m -> latest.containsKey(m) && !latest.get(m).isJoined()).count();
            int stayed = people.size() - left;
            Double perSub = l.getCost() == null || stayed == 0 ? null : Math.round(l.getCost() / stayed * 100) / 100.0;
            out.add(new LinkStat(l.getId(), l.getName(), l.getUrl(), l.getCost(), l.getCurrency(), l.getCreatedAt(),
                    l.getRevokedAt() != null, people.size(), left, stayed, perSub,
                    kept(joins, history, Duration.ofDays(7), now), kept(joins, history, Duration.ofDays(30), now)));
        }
        return out;
    }

    /** Of the people who joined through a link at least {@code after} ago, the share who hadn't left {@code after} later. */
    static Double kept(List<MemberEvent> joins, Map<Long, List<MemberEvent>> history, Duration after, Instant now) {
        int eligible = 0, kept = 0;
        Set<Long> seen = new HashSet<>();
        for (MemberEvent j : joins) {
            if (!seen.add(j.getMember()) || j.getAt().plus(after).isAfter(now)) continue;
            eligible++;
            Instant until = j.getAt().plus(after);
            boolean gone = history.getOrDefault(j.getMember(), List.of()).stream()
                    .anyMatch(e -> !e.isJoined() && !e.getAt().isBefore(j.getAt()) && e.getAt().isBefore(until));
            if (!gone) kept++;
        }
        return eligible == 0 ? null : Math.round(kept * 1000.0 / eligible) / 1000.0;
    }

    // ------------------------------------------------------------------ demo

    /** Gives a sample channel two months of joins and leaves and three promo links the first time it's looked at. */
    private void ensureDemo(Channel ch) {
        if (events.findFirstByChannelIdOrderByAtAsc(ch.getId()).isPresent() || links.countByChannelId(ch.getId()) > 0) return;
        Instant now = Instant.now();
        Random rng = new Random(ch.getTelegramChatId());
        int subs = ch.getSubscribers() == null ? 5_000 : Math.max(500, ch.getSubscribers());
        List<MemberEvent> rows = new ArrayList<>();
        List<Long> joiners = new ArrayList<>();
        Instant from = now.minus(Duration.ofDays(60));

        // organic: a steady trickle, more after well-liked posts and some leaves after link-heavy ones
        double perHour = subs * 0.0012 / 24;
        for (Instant t = from; t.isBefore(now); t = t.plus(Duration.ofHours(1))) {
            int j = poisson(rng, perHour), l = poisson(rng, perHour * 0.55);
            for (int k = 0; k < j; k++) rows.add(join(ch, t, rng, null, joiners));
            for (int k = 0; k < l; k++) rows.add(leave(ch, t, rng, joiners));
        }
        for (Post p : posts.findAllByChannelIdAndPostedAtAfterOrderByPostedAtAsc(ch.getId(), from)) {
            int lift = (int) Math.round(p.getReactions() / 18.0 + rng.nextInt(3));
            int lost = p.isHasLink() ? 2 + rng.nextInt(6) : rng.nextInt(2);
            for (int k = 0; k < lift; k++) rows.add(join(ch, p.getPostedAt().plus(Duration.ofMinutes(5 + rng.nextInt(600))), rng, null, joiners));
            for (int k = 0; k < lost; k++) rows.add(leave(ch, p.getPostedAt().plus(Duration.ofMinutes(2 + rng.nextInt(180))), rng, joiners));
        }

        // three promos: a paid shoutout, a free bio link and paid ads with a weaker audience
        Object[][] promos = {{"Shoutout in a partner channel", 20.0, 14, 110, 0.18}, {"Link in my bio", null, 27, 40, 0.08},
                {"Telegram Ads test", 45.0, 6, 70, 0.42}};
        for (Object[] pr : promos) {
            PromoLink l = new PromoLink();
            l.setChannelId(ch.getId());
            l.setName((String) pr[0]);
            l.setUrl("https://t.me/+demo" + Long.toString(Math.abs(rng.nextLong()), 36));
            l.setCost((Double) pr[1]);
            l.setCurrency(pr[1] == null ? null : "$");
            l.setCreatedAt(now.minus(Duration.ofDays((Integer) pr[2])));
            l = links.save(l);
            int count = (int) Math.round((Integer) pr[3] * subs / 12_000.0) + 5;
            boolean burst = pr[1] != null;
            for (int k = 0; k < count; k++) {
                long minutes = burst ? (long) (Math.abs(rng.nextGaussian()) * 600) : rng.nextInt((Integer) pr[2] * 24 * 60);
                Instant at = l.getCreatedAt().plus(Duration.ofMinutes(minutes));
                if (at.isAfter(now)) at = now.minusSeconds(60);
                long who = rng.nextLong();
                rows.add(new MemberEvent(ch.getId(), at, true, who, l.getId()));
                if (rng.nextDouble() < (Double) pr[4]) {
                    Instant gone = at.plus(Duration.ofHours(2 + rng.nextInt(96)));
                    if (gone.isBefore(now)) rows.add(new MemberEvent(ch.getId(), gone, false, who, null));
                }
            }
        }
        rows.removeIf(e -> e.getAt().isAfter(now));
        events.saveAll(rows);
    }

    private static MemberEvent join(Channel ch, Instant at, Random rng, Long link, List<Long> joiners) {
        long who = rng.nextLong();
        joiners.add(who);
        return new MemberEvent(ch.getId(), at, true, who, link);
    }

    /** Someone leaves: now and then one of the people who joined this month, otherwise an older subscriber. */
    private static MemberEvent leave(Channel ch, Instant at, Random rng, List<Long> joiners) {
        long who = !joiners.isEmpty() && rng.nextInt(4) == 0 ? joiners.remove(rng.nextInt(joiners.size())) : rng.nextLong();
        return new MemberEvent(ch.getId(), at, false, who, null);
    }

    private static int poisson(Random rng, double mean) {
        double l = Math.exp(-mean), p = 1;
        int k = 0;
        do {
            k++;
            p *= rng.nextDouble();
        } while (p > l);
        return k - 1;
    }
}
