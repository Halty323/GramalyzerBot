package com.halty.gramalyzerbot.crosspromo;

import com.halty.gramalyzerbot.channel.*;
import com.halty.gramalyzerbot.common.UserFacingException;
import com.halty.gramalyzerbot.growth.GrowthService;
import com.halty.gramalyzerbot.growth.GrowthService.LinkStat;
import com.halty.gramalyzerbot.growth.MemberEvent;
import com.halty.gramalyzerbot.growth.MemberEventRepository;
import com.halty.gramalyzerbot.growth.PromoLink;
import com.halty.gramalyzerbot.growth.PromoLinkRepository;
import com.halty.gramalyzerbot.i18n.Lang;
import com.halty.gramalyzerbot.post.PostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Cross-promo between channels that use Gramalyzer. A channel opts in with its topic and language; partners are
 * channels in the same language with a close topic and a similar size and engagement. When a partner accepts a swap,
 * each side gets a promo link to post in the other's channel, so both see exactly who the other's post brought.
 * <p>
 * Partners see each other's username and the owner's contact only once a swap is accepted.
 */
@Service
@RequiredArgsConstructor
public class SwapService {

    /** At most this many unanswered requests out of one channel at a time. */
    static final int MAX_OUTGOING = 5;
    static final int MAX_MATCHES = 10;
    static final int MAX_PITCH = 300;
    /** The smaller channel has at least this share of the bigger one's subscribers. */
    static final double MIN_SIZE_RATIO = 0.2;
    private static final Duration ENGAGEMENT_WINDOW = Duration.ofDays(30);
    private static final long DEMO_PARTNER_BASE = -4_100_000_000_000_000_000L;
    private static final Set<Swap.Status> OPEN = EnumSet.of(Swap.Status.REQUESTED, Swap.Status.ACTIVE);

    private final SwapProfileRepository profiles;
    private final SwapRepository swaps;
    private final ChannelRepository channelRepo;
    private final ChannelService channels;
    private final ChannelMemberRepository members;
    private final com.halty.gramalyzerbot.user.UserRepository users;
    private final PostRepository posts;
    private final GrowthService growth;
    private final PromoLinkRepository promoLinks;
    private final MemberEventRepository memberEvents;
    private final ApplicationEventPublisher events;

    /** A possible partner. {@code score} is 0–100; the partner's name stays hidden until a swap is accepted. */
    public record Match(long channelId, String title, Topic topic, String language, String pitch, Integer subscribers,
                        Double engagement, int score) {}

    /**
     * A swap from one side's point of view. {@code mine} is the link in this channel that the partner posts (who the
     * partner brought here), {@code theirs} the link in the partner's channel that this channel posts.
     */
    public record SwapView(long id, Swap.Status status, boolean outgoing, long partnerId, String partnerTitle, Topic partnerTopic,
                           Integer partnerSubscribers, String partnerPitch, String partnerUrl, String partnerContact,
                           Instant createdAt, Instant respondedAt, LinkStat mine, LinkStat theirs) {
        public boolean incoming() { return !outgoing && status == Swap.Status.REQUESTED; }
    }

    /** Something the other side should hear about: a new request, an answer, or the end of a swap. */
    public record Notice(Swap swap, long recipientChannel, Kind kind) {
        public enum Kind { REQUESTED, ACCEPTED, DECLINED, CANCELLED, ENDED }
    }

    // ------------------------------------------------------------------ profile

    @Transactional
    public Optional<SwapProfile> profile(Channel ch) {
        if (ch.isDemo()) ensureDemo(ch);
        return profiles.findById(ch.getId());
    }

    @Transactional
    public SwapProfile saveProfile(Channel ch, Topic topic, String language, String pitch, boolean enabled) {
        if (topic == null) throw new UserFacingException("swap.err.topic");
        String lang = Arrays.stream(Lang.values()).map(Lang::code).filter(c -> c.equals(language)).findFirst()
                .orElseThrow(() -> new UserFacingException("swap.err.language"));
        SwapProfile p = profiles.findById(ch.getId()).orElseGet(() -> {
            SwapProfile n = new SwapProfile();
            n.setChannelId(ch.getId());
            return n;
        });
        p.setTopic(topic);
        p.setLanguage(lang);
        if (pitch != null) {
            String t = pitch.strip();
            if (t.codePointCount(0, t.length()) > MAX_PITCH) t = t.substring(0, t.offsetByCodePoints(0, MAX_PITCH));
            p.setPitch(t.isEmpty() ? null : t);
        }
        p.setEnabled(enabled);
        p.setUpdatedAt(Instant.now());
        return profiles.save(p);
    }

    // ------------------------------------------------------------------ matching

    /** The best partners for a channel, best first. Empty until the channel has an enabled profile. */
    @Transactional
    public List<Match> matches(Channel ch) {
        Optional<SwapProfile> mine = profile(ch).filter(SwapProfile::isEnabled);
        if (mine.isEmpty()) return List.of();
        Set<Long> owners = owners(ch.getId());
        Set<Long> busy = new HashSet<>();
        for (Swap s : swaps.involving(ch.getId())) if (s.getStatus().open()) busy.add(s.partnerOf(ch.getId()));
        Double myEngagement = engagement(ch);

        List<SwapProfile> candidates = profiles.findAllByEnabledTrueAndLanguage(mine.get().getLanguage()).stream()
                .filter(p -> !p.getChannelId().equals(ch.getId()) && !busy.contains(p.getChannelId())
                        && mine.get().getTopic().closeness(p.getTopic()) > 0).toList();
        Map<Long, Channel> byId = new HashMap<>();
        for (Channel c : channelRepo.findAllById(candidates.stream().map(SwapProfile::getChannelId).toList())) byId.put(c.getId(), c);

        List<Match> out = new ArrayList<>();
        for (SwapProfile p : candidates) {
            Channel c = byId.get(p.getChannelId());
            if (c == null || !c.isActive() || c.isDemo() != ch.isDemo() || (ch.isDemo() && !isDemoPartner(c))) continue;
            Double size = closeness(ch.getSubscribers(), c.getSubscribers());
            if (size != null && size < MIN_SIZE_RATIO) continue;
            if (!owners.isEmpty() && !Collections.disjoint(owners, owners(c.getId()))) continue;
            Double eng = engagement(c);
            double score = score(mine.get().getTopic().closeness(p.getTopic()), size, closeness(myEngagement, eng));
            out.add(new Match(c.getId(), c.getTitle(), p.getTopic(), p.getLanguage(), p.getPitch(), c.getSubscribers(),
                    eng == null ? null : Math.round(eng * 10_000) / 100.0, (int) Math.round(score * 100)));
        }
        out.sort(Comparator.comparingInt(Match::score).reversed().thenComparing(Match::channelId));
        return out.subList(0, Math.min(MAX_MATCHES, out.size()));
    }

    /** Topic counts most, then size, then how engaged the audience is; an unknown size or engagement counts as half. */
    static double score(double topic, Double size, Double engagement) {
        return 0.45 * topic + 0.35 * (size == null ? 0.5 : size) + 0.2 * (engagement == null ? 0.5 : engagement);
    }

    /** The smaller over the bigger, 0–1; null when either is unknown. */
    static Double closeness(Number a, Number b) {
        if (a == null || b == null) return null;
        double x = a.doubleValue(), y = b.doubleValue();
        if (x <= 0 || y <= 0) return x == y ? 1.0 : 0.0;
        return Math.min(x, y) / Math.max(x, y);
    }

    /** Average reactions per post over the last month, as a share of subscribers. */
    private Double engagement(Channel c) {
        if (c.getSubscribers() == null || c.getSubscribers() <= 0) return null;
        Double avg = posts.averageReactions(c.getId(), Instant.now().minus(ENGAGEMENT_WINDOW));
        return avg == null ? null : avg / c.getSubscribers();
    }

    private Set<Long> owners(long channelId) {
        Set<Long> out = new HashSet<>();
        for (ChannelMember m : members.findAllByChannelId(channelId)) if (m.getRole() == Role.OWNER) out.add(m.getUserId());
        return out;
    }

    // ------------------------------------------------------------------ swaps

    @Transactional
    public List<SwapView> swaps(Channel ch) {
        if (ch.isDemo()) ensureDemo(ch);
        List<Swap> all = swaps.involving(ch.getId());
        if (all.isEmpty()) return List.of();
        Map<Long, LinkStat> myLinks = new HashMap<>();
        for (LinkStat l : growth.linkStats(ch)) myLinks.put(l.id(), l);
        List<SwapView> out = new ArrayList<>();
        for (Swap s : all) {
            Channel partner = channelRepo.findById(s.partnerOf(ch.getId())).orElse(null);
            if (partner != null) out.add(view(ch, s, partner, myLinks));
        }
        // what needs an answer first, then running swaps, then the rest by date
        out.sort(Comparator.comparingInt((SwapView v) -> v.incoming() ? 0 : v.status() == Swap.Status.ACTIVE ? 1 : v.status() == Swap.Status.REQUESTED ? 2 : 3)
                .thenComparing(SwapView::createdAt, Comparator.reverseOrder()));
        return out;
    }

    @Transactional
    public SwapView swapView(Channel ch, long swapId) {
        Swap s = swap(ch, swapId);
        Channel partner = channels.get(s.partnerOf(ch.getId()));
        Map<Long, LinkStat> myLinks = new HashMap<>();
        for (LinkStat l : growth.linkStats(ch)) myLinks.put(l.id(), l);
        return view(ch, s, partner, myLinks);
    }

    private SwapView view(Channel ch, Swap s, Channel partner, Map<Long, LinkStat> myLinks) {
        boolean outgoing = s.getFromChannel().equals(ch.getId());
        Long mineId = outgoing ? s.getFromLink() : s.getToLink();
        Long theirsId = outgoing ? s.getToLink() : s.getFromLink();
        LinkStat theirs = theirsId == null ? null
                : growth.linkStats(partner).stream().filter(l -> l.id() == theirsId).findFirst().orElse(null);
        boolean known = s.getStatus() == Swap.Status.ACTIVE || s.getStatus() == Swap.Status.DONE;
        SwapProfile pp = profiles.findById(partner.getId()).orElse(null);
        return new SwapView(s.getId(), s.getStatus(), outgoing, partner.getId(), partner.getTitle(), pp == null ? null : pp.getTopic(),
                partner.getSubscribers(), pp == null ? null : pp.getPitch(),
                known ? Links.channel(partner.getUsername(), partner.isDemo()) : null, known ? contact(partner) : null,
                s.getCreatedAt(), s.getRespondedAt(), mineId == null ? null : myLinks.get(mineId), theirs);
    }

    /** "@owner" of a channel, to agree on the details. */
    private String contact(Channel c) {
        for (ChannelMember m : members.findAllByChannelId(c.getId())) {
            if (m.getRole() != Role.OWNER) continue;
            String name = users.findById(m.getUserId()).map(u -> u.getUsername()).orElse(null);
            if (name != null && !name.isBlank()) return "@" + name;
        }
        return null;
    }

    public Swap swap(Channel ch, long swapId) {
        return swaps.findById(swapId).filter(s -> s.involves(ch.getId())).orElseThrow(() -> new UserFacingException("swap.err.gone"));
    }

    /** Asks a partner from {@link #matches} to swap posts. */
    @Transactional
    public Swap propose(Channel from, long toChannelId) {
        SwapProfile mine = profile(from).filter(SwapProfile::isEnabled).orElseThrow(() -> new UserFacingException("swap.err.no_profile"));
        if (toChannelId == from.getId()) throw new UserFacingException("swap.err.gone");
        if (!swaps.between(from.getId(), toChannelId, OPEN).isEmpty()) throw new UserFacingException("swap.err.already");
        if (swaps.countByFromChannelAndStatus(from.getId(), Swap.Status.REQUESTED) >= MAX_OUTGOING)
            throw new UserFacingException("swap.err.too_many", MAX_OUTGOING);
        if (matches(from).stream().noneMatch(m -> m.channelId() == toChannelId)) throw new UserFacingException("swap.err.not_a_match");
        Swap s = new Swap();
        s.setFromChannel(from.getId());
        s.setToChannel(toChannelId);
        s.setCreatedAt(Instant.now());
        s = swaps.save(s);
        Channel to = channels.get(toChannelId);
        if (isDemoPartner(to)) return start(s, from, to, Instant.now()); // sample partners always say yes
        events.publishEvent(new Notice(s, toChannelId, Notice.Kind.REQUESTED));
        return s;
    }

    /** Says yes to a request: both channels get a promo link for the other to post. */
    @Transactional
    public Swap accept(Channel ch, long swapId) {
        Swap s = swap(ch, swapId);
        if (s.getStatus() != Swap.Status.REQUESTED || !s.getToChannel().equals(ch.getId())) throw new UserFacingException("swap.err.answered");
        Swap started = start(s, channels.get(s.getFromChannel()), ch, Instant.now());
        events.publishEvent(new Notice(started, started.getFromChannel(), Notice.Kind.ACCEPTED));
        return started;
    }

    private Swap start(Swap s, Channel from, Channel to, Instant at) {
        s.setFromLink(growth.create(from, linkName(to), null, null).getId());
        s.setToLink(growth.create(to, linkName(from), null, null).getId());
        s.setStatus(Swap.Status.ACTIVE);
        s.setRespondedAt(at);
        return swaps.save(s);
    }

    private static String linkName(Channel partner) { return "Swap: " + partner.getTitle(); }

    @Transactional
    public Swap decline(Channel ch, long swapId) {
        Swap s = swap(ch, swapId);
        if (s.getStatus() != Swap.Status.REQUESTED || !s.getToChannel().equals(ch.getId())) throw new UserFacingException("swap.err.answered");
        return close(s, Swap.Status.DECLINED, s.getFromChannel(), Notice.Kind.DECLINED);
    }

    /** Takes back an unanswered request. */
    @Transactional
    public Swap cancel(Channel ch, long swapId) {
        Swap s = swap(ch, swapId);
        if (s.getStatus() != Swap.Status.REQUESTED || !s.getFromChannel().equals(ch.getId())) throw new UserFacingException("swap.err.answered");
        return close(s, Swap.Status.CANCELLED, s.getToChannel(), Notice.Kind.CANCELLED);
    }

    /** Marks a running swap as finished; its links keep working and counting, so late joins still show. */
    @Transactional
    public Swap end(Channel ch, long swapId) {
        Swap s = swap(ch, swapId);
        if (s.getStatus() != Swap.Status.ACTIVE) throw new UserFacingException("swap.err.answered");
        return close(s, Swap.Status.DONE, s.partnerOf(ch.getId()), Notice.Kind.ENDED);
    }

    private Swap close(Swap s, Swap.Status status, long tell, Notice.Kind kind) {
        s.setStatus(status);
        if (s.getRespondedAt() == null || status != Swap.Status.DONE) s.setRespondedAt(Instant.now());
        s = swaps.save(s);
        Channel other = channelRepo.findById(tell).orElse(null);
        if (other != null && !isDemoPartner(other)) events.publishEvent(new Notice(s, tell, kind));
        return s;
    }

    // ------------------------------------------------------------------ demo

    private record DemoPartner(String title, String username, Topic topic, String language, int subscribers, String pitch) {}

    private static final List<DemoPartner> DEMO_PARTNERS = List.of(
            new DemoPartner("⚙️ Code & Coffee", "code_coffee_demo", Topic.TECH, "en", 11_200, "Daily dev tips and tools. Our readers are mostly backend developers."),
            new DemoPartner("🚀 Startup Notes", "startup_notes_demo", Topic.BUSINESS, "en", 9_400, null),
            new DemoPartner("🤖 AI Weekly", "ai_weekly_demo", Topic.TECH, "en", 15_800, "The week in AI, in five minutes. Happy to swap with tech and science channels."),
            new DemoPartner("🔭 Space Facts", "space_facts_demo", Topic.SCIENCE, "en", 7_300, "Pictures and short facts about space."),
            new DemoPartner("🎮 Indie Games Daily", "indie_games_demo", Topic.GAMING, "en", 13_100, "New indie games every day."),
            new DemoPartner("📱 Gadget Drops", "gadget_drops_demo", Topic.TECH, "en", 41_000, null),
            new DemoPartner("🧪 Lab Stories", "lab_stories_demo", Topic.SCIENCE, "en", 2_100, null),
            new DemoPartner("💻 Код українською", "kod_ua_demo", Topic.TECH, "uk", 8_900, "Програмування українською, щодня."));

    static boolean isDemoPartner(Channel c) {
        return c.isDemo() && c.getTelegramChatId() <= DEMO_PARTNER_BASE && c.getTelegramChatId() > DEMO_PARTNER_BASE - DEMO_PARTNERS.size() - 1;
    }

    /**
     * Gives a sample channel a profile, a running swap with a few joins and one request to answer, and makes the
     * sample partners that it can be matched with. Only the first time; the partners are shared by every sample channel.
     */
    private void ensureDemo(Channel ch) {
        if (isDemoPartner(ch) || profiles.existsById(ch.getId())) return;
        List<Channel> partners = demoPartners();
        growth.linkStats(ch); // the sample growth data first: it's only made for a channel without promo links
        SwapProfile p = new SwapProfile();
        p.setChannelId(ch.getId());
        p.setTopic(Topic.TECH);
        p.setLanguage("en");
        p.setPitch("Tech news and practical tips, two posts a day.");
        p.setUpdatedAt(Instant.now());
        profiles.save(p);

        Instant now = Instant.now();
        Random rng = new Random(ch.getId());
        // a swap that has run for nine days, with Code & Coffee
        Swap running = new Swap();
        running.setFromChannel(ch.getId());
        running.setToChannel(partners.get(0).getId());
        running.setCreatedAt(now.minus(Duration.ofDays(10)));
        running = start(swaps.save(running), ch, partners.get(0), now.minus(Duration.ofDays(9)));
        backdate(running.getFromLink(), now.minus(Duration.ofDays(9)), 64, 0.15, rng);
        backdate(running.getToLink(), now.minus(Duration.ofDays(9)), 51, 0.2, rng);
        // and AI Weekly asking for one
        Swap asking = new Swap();
        asking.setFromChannel(partners.get(2).getId());
        asking.setToChannel(ch.getId());
        asking.setCreatedAt(now.minus(Duration.ofHours(5)));
        swaps.save(asking);
    }

    /** Moves a sample link's start back and gives it joins over the following days, some of whom left again. */
    private void backdate(long linkId, Instant since, int joins, double leaveShare, Random rng) {
        PromoLink l = promoLinks.findById(linkId).orElseThrow();
        l.setCreatedAt(since);
        promoLinks.save(l);
        Instant now = Instant.now();
        List<MemberEvent> rows = new ArrayList<>();
        for (int i = 0; i < joins; i++) {
            Instant at = since.plus(Duration.ofMinutes((long) (Math.abs(rng.nextGaussian()) * 900) + 3));
            if (at.isAfter(now)) at = now.minusSeconds(60);
            long who = rng.nextLong();
            rows.add(new MemberEvent(l.getChannelId(), at, true, who, linkId));
            Instant gone = at.plus(Duration.ofHours(3 + rng.nextInt(120)));
            if (rng.nextDouble() < leaveShare && gone.isBefore(now)) rows.add(new MemberEvent(l.getChannelId(), gone, false, who, null));
        }
        memberEvents.saveAll(rows);
    }

    private List<Channel> demoPartners() {
        List<Channel> out = new ArrayList<>();
        for (int i = 0; i < DEMO_PARTNERS.size(); i++) {
            DemoPartner d = DEMO_PARTNERS.get(i);
            long chatId = DEMO_PARTNER_BASE - i;
            Channel c = channels.byChatId(chatId).orElse(null);
            if (c == null) {
                c = channels.register(chatId, d.title(), d.username(), d.subscribers());
                c.setDemo(true);
                c.setHistoryImported(true);
                c = channels.save(c);
                SwapProfile p = new SwapProfile();
                p.setChannelId(c.getId());
                p.setTopic(d.topic());
                p.setLanguage(d.language());
                p.setPitch(d.pitch());
                p.setUpdatedAt(Instant.now());
                profiles.save(p);
            }
            out.add(c);
        }
        return out;
    }
}
