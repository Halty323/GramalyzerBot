package com.halty.gramalyzerbot.tracking;

import com.halty.gramalyzerbot.bot.BotApi;
import com.halty.gramalyzerbot.tracking.PreviewParser.Preview;
import com.halty.gramalyzerbot.tracking.PreviewParser.PreviewPost;
import com.halty.gramalyzerbot.tracking.TrackingException.Code;
import com.halty.gramalyzerbot.user.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Keeps an eye on public channels nobody had to add the bot to. Everything comes from what Telegram shows anyone:
 * the channel's web preview ({@code t.me/s/<username>}: title, counters, the latest posts with views and reactions)
 * and, when the bot is running, {@code getChat} and {@code getChatMemberCount}, which answer for public channels too.
 * <p>
 * Channels are shared: a channel watched by the website and by three people in the bot is fetched once. Each refresh
 * updates the posts still on the preview page and the day's snapshot, which builds the subscriber history over time.
 */
@Service
@Slf4j
public class TrackingService {

    /** The website's single workspace. */
    public static final String WEB = "web";

    /** Telegram usernames: 5–32 characters, start with a letter. Short or reserved ones are rejected by Telegram itself. */
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{3,31}");
    private static final Pattern LINK = Pattern.compile("^(?:https?://)?(?:www\\.)?(?:t\\.me|telegram\\.me|telegram\\.dog)/(?:s/)?([^/?#]+)(?:[/?#].*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESOLVE = Pattern.compile("^tg://resolve\\?domain=([A-Za-z0-9_]+).*$", Pattern.CASE_INSENSITIVE);
    /** A preview this recent is served as is. */
    private static final Duration FRESH = Duration.ofMinutes(10);
    /** The same for the workspace's own channels: their owner wants to see the post they just published. */
    private static final Duration OWN_FRESH = Duration.ofMinutes(2);
    /** A channel seen for the first time is read back this far (about 20 posts a page), so its stats start from real history. */
    private static final int BACKFILL_POSTS = 60;
    private static final int BACKFILL_PAGES = 3;
    /** More posts than one page holds since the last refresh: older pages are read until they meet the stored ones. */
    private static final int GAP_PAGES = 10;
    /** Kinds whose pictures {@link FeedPost#media()} lists. */
    private static final Set<String> PICTURES = Set.of("PHOTO", "ALBUM", "VIDEO", "ANIMATION");

    private final TrackedChannelRepository channels;
    private final TrackedPostRepository posts;
    private final TrackedSnapshotRepository snapshots;
    private final TrackedWatcherRepository watchers;
    private final PreviewClient client;
    private final BotApi bot;
    private final TransactionTemplate tx;
    private final int maxPerPerson;
    private final int maxWeb;

    public TrackingService(TrackedChannelRepository channels, TrackedPostRepository posts, TrackedSnapshotRepository snapshots,
                           TrackedWatcherRepository watchers, PreviewClient client, BotApi bot, TransactionTemplate tx,
                           @Value("${gramalyzer.tracking.max-per-person:20}") int maxPerPerson,
                           @Value("${gramalyzer.tracking.max-web:100}") int maxWeb) {
        this.channels = channels;
        this.posts = posts;
        this.snapshots = snapshots;
        this.watchers = watchers;
        this.client = client;
        this.bot = bot;
        this.tx = tx;
        this.maxPerPerson = maxPerPerson;
        this.maxWeb = maxWeb;
    }

    /** A person's watchlist in the bot. */
    public static String owner(User u) { return "tg:" + u.getId(); }

    // ------------------------------------------------------------------ usernames

    /**
     * Turns what people paste into a username: {@code @name}, {@code name}, {@code t.me/name}, {@code https://t.me/s/name/42},
     * {@code tg://resolve?domain=name}. Lower-case, without "@".
     */
    public static String username(String input) {
        String s = input == null ? "" : input.trim();
        Matcher link = LINK.matcher(s);
        if (link.matches()) {
            s = link.group(1);
            if (s.startsWith("+") || s.equalsIgnoreCase("joinchat") || s.equalsIgnoreCase("c")) throw new TrackingException(Code.PRIVATE_LINK);
        } else {
            Matcher resolve = RESOLVE.matcher(s);
            if (resolve.matches()) s = resolve.group(1);
        }
        s = s.replaceFirst("^@", "");
        if (!USERNAME.matcher(s).matches()) throw new TrackingException(Code.INVALID_USERNAME);
        return s.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ watchlists

    public List<TrackedChannel> watched(String owner) {
        List<Long> ids = watchers.findAllByOwnerOrderByCreatedAtAsc(owner).stream().map(TrackedWatcher::getTrackedChannelId).toList();
        Map<Long, TrackedChannel> byId = channels.findAllById(ids).stream().collect(Collectors.toMap(TrackedChannel::getId, Function.identity()));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    public boolean watching(String owner, TrackedChannel c) {
        return watchers.findByTrackedChannelIdAndOwner(c.getId(), owner).isPresent();
    }

    public long watchCount(String owner) { return watchers.countByOwner(owner); }

    /** Adds a channel to a watchlist, fetching it first if nobody has looked at it recently. */
    public TrackedChannel track(String owner, String input) {
        return track(owner, input, WEB.equals(owner) ? maxWeb : maxPerPerson);
    }

    /** Same, with the watchlist size the person's plan allows. */
    public TrackedChannel track(String owner, String input, int max) {
        String username = username(input);
        TrackedChannel c = lookup(username);
        if (watching(owner, c)) return c;
        if (watchers.countByOwner(owner) >= max) throw new TrackingException(Code.LIMIT);
        try {
            watchers.save(new TrackedWatcher(c.getId(), owner));
        } catch (org.springframework.dao.DataIntegrityViolationException alreadyThere) {
            // the same person tracked it twice at once
        }
        return c;
    }

    public void untrack(String owner, String input) {
        TrackedChannel c = channels.findByUsername(username(input)).orElseThrow(() -> new TrackingException(Code.NOT_TRACKED));
        TrackedWatcher w = watchers.findByTrackedChannelIdAndOwner(c.getId(), owner).orElseThrow(() -> new TrackingException(Code.NOT_TRACKED));
        watchers.delete(w);
    }

    // ------------------------------------------------------------------ channels

    /** The channel as last fetched, fetching it now if it's new or stale. Doesn't add it to any watchlist. */
    public TrackedChannel lookup(String input) {
        String username = username(input);
        Optional<TrackedChannel> known = channels.findByUsername(username);
        if (known.isPresent()) return refresh(known.get(), false);
        return fetch(username, null);
    }

    /**
     * One of the workspace's own public channels. It is kept for good, so its posts build up in the database instead of
     * being forgotten after a few days like a channel somebody only looked at, and the scheduled refresh visits it. It
     * is fetched again when the last fetch is more than two minutes old.
     */
    public TrackedChannel own(String workspaceChannelId, String input) {
        String username = username(input);
        Optional<TrackedChannel> known = channels.findByUsername(username);
        TrackedChannel c = known.isPresent() ? refresh(known.get(), false, OWN_FRESH) : fetch(username, null);
        String owner = "own:" + workspaceChannelId;
        // the channel was renamed: its old username is somebody else's now
        for (TrackedWatcher w : watchers.findAllByOwnerOrderByCreatedAtAsc(owner))
            if (!w.getTrackedChannelId().equals(c.getId())) watchers.delete(w);
        if (!watching(owner, c)) {
            try {
                watchers.save(new TrackedWatcher(c.getId(), owner));
            } catch (org.springframework.dao.DataIntegrityViolationException alreadyThere) {
                // two feed requests at once
            }
        }
        return c;
    }

    /** A channel somebody already looked at; never fetches. */
    public Optional<TrackedChannel> find(String input) { return channels.findByUsername(username(input)); }

    /** A channel by its id, for the bot's buttons; never fetches. */
    public TrackedChannel get(long id) { return channels.findById(id).orElseThrow(() -> new TrackingException(Code.NOT_FOUND)); }

    /**
     * Fetches the channel again unless the last fetch is recent (or {@code force}). A failed refresh of a channel we
     * already know keeps the old data and records the reason in {@code lastError}.
     */
    public TrackedChannel refresh(TrackedChannel c, boolean force) {
        return refresh(c, force, FRESH);
    }

    /** Fetches the channel again unless it was read within {@code fresh}; never throws for a channel already known. */
    public TrackedChannel refreshIfOlder(TrackedChannel c, Duration fresh) {
        return refresh(c, false, fresh);
    }

    private TrackedChannel refresh(TrackedChannel c, boolean force, Duration fresh) {
        if (c.isDemo()) return c;
        if (!force && c.getFetchedAt() != null && c.getFetchedAt().isAfter(Instant.now().minus(fresh)) && c.getLastError() == null) return c;
        try {
            return fetch(c.getUsername(), c);
        } catch (TrackingException e) {
            if (e.code() == Code.NOT_FOUND || e.code() == Code.NOT_CHANNEL || e.code() == Code.UNREACHABLE) {
                c.setLastError(e.code().key());
                channels.save(c);
            }
            if (force) throw e;
            return c;
        }
    }

    public List<TrackedPost> posts(TrackedChannel c) {
        return posts.findTop60ByTrackedChannelIdOrderByMessageIdDesc(c.getId());
    }

    public TrackedStats stats(TrackedChannel c, ZoneId zone) {
        List<TrackedSnapshot> history = snapshots.findAllByTrackedChannelIdAndDayAfterOrderByDayAsc(c.getId(), LocalDate.now(ZoneOffset.UTC).minusDays(91));
        return TrackedStats.of(c.getSubscribers(), posts(c), history, Instant.now(), zone);
    }

    /** Channels on at least one watchlist that the scheduled refresh should visit. */
    public List<TrackedChannel> watchedAnywhere() { return channels.findAllWatched(); }

    /** Forgets channels somebody only looked at, after a while; their pages can always be fetched again. */
    public int forgetUnwatched(Duration olderThan) {
        List<TrackedChannel> stale = channels.findUnwatchedCreatedBefore(Instant.now().minus(olderThan));
        for (TrackedChannel c : stale) delete(c);
        return stale.size();
    }

    void delete(TrackedChannel c) {
        tx.executeWithoutResult(s -> {
            posts.deleteAllByChannel(c.getId());
            snapshots.deleteAllByChannel(c.getId());
            channels.deleteById(c.getId());
        });
    }

    // ------------------------------------------------------------------ fetching

    private TrackedChannel fetch(String username, TrackedChannel existing) {
        Preview preview = null;
        TrackingException failure = null;
        Integer newest = existing == null ? null
                : posts.findFirstByTrackedChannelIdOrderByMessageIdDesc(existing.getId()).map(TrackedPost::getMessageId).orElse(null);
        // posts with pictures stored before every picture of an album was read: their pages are read again, once
        Integer upTo = existing == null ? null : posts(existing).stream()
                .filter(t -> t.getMedia() == null && t.getMediaUrl() != null && PICTURES.contains(t.getKind()))
                .map(TrackedPost::getMessageId).min(Integer::compare).orElse(newest);
        try {
            preview = readPreview(username, newest, upTo);
        } catch (TrackingException e) {
            failure = e;
        }
        Optional<BotApi.ChatInfo> api = bot.enabled() ? bot.chat("@" + username) : Optional.empty();
        if (api.isPresent() && !"channel".equals(api.get().type())) throw new TrackingException(Code.NOT_CHANNEL);
        if (preview == null && api.isEmpty()) throw failure;
        Integer exact = api.isPresent() ? bot.memberCount("@" + username).orElse(null) : null;

        TrackedChannel c = existing != null ? existing : new TrackedChannel(username, username);
        if (preview != null) {
            c.setTitle(trunc(preview.title(), 255));
            c.setDescription(trunc(preview.description(), 2048));
            c.setPhotoUrl(trunc(preview.photoUrl(), 1024));
            c.setVerified(preview.verified());
            if (preview.subscribers() != null) c.setSubscribers(preview.subscribers());
            if (!preview.counters().isEmpty()) {
                c.setPhotos(preview.counters().get("photo"));
                c.setVideos(preview.counters().get("video"));
                c.setFiles(preview.counters().get("file"));
                c.setLinks(preview.counters().get("link"));
            }
        }
        if (api.isPresent()) {
            BotApi.ChatInfo info = api.get();
            c.setTelegramChatId(info.id());
            if (info.title() != null) c.setTitle(trunc(info.title(), 255));
            if (info.description() != null) c.setDescription(trunc(info.description(), 2048));
        }
        if (exact != null) c.setSubscribers(exact);
        c.setFetchedAt(Instant.now());
        c.setLastError(null);
        TrackedChannel saved;
        try {
            saved = channels.save(c);
        } catch (org.springframework.dao.DataIntegrityViolationException raced) {
            // somebody else fetched the same new channel a moment ago
            saved = channels.findByUsername(username).orElseThrow(() -> raced);
        }
        if (preview != null) store(saved, preview);
        snapshot(saved);
        return saved;
    }

    /**
     * The preview page. The first time ({@code newest} is null: nothing stored yet), older pages too, so the stats start
     * from ~60 posts instead of ~20. Later, when more posts came out than the page holds, older pages until they reach
     * the newest stored post, so none goes missing in between, or further back to message {@code upTo}.
     */
    private Preview readPreview(String username, Integer newest, Integer upTo) {
        try {
            PreviewClient.Page page = client.preview(username, null);
            if (page.status() == 404) throw new TrackingException(Code.NOT_FOUND);
            if (page.status() >= 500 || page.status() == 429) throw new TrackingException(Code.UNREACHABLE);
            Optional<Preview> preview = PreviewParser.preview(page.html(), username);
            if (preview.isEmpty()) {
                // t.me/s/ redirects everything that isn't a channel with a preview to the plain page
                String html = page.url().contains("/s/") ? client.page(username).html() : page.html();
                return switch (PreviewParser.page(html)) {
                    case CHANNEL -> PreviewParser.fallback(html, username).orElseThrow(() -> new TrackingException(Code.NOT_FOUND));
                    case GROUP, OTHER -> throw new TrackingException(Code.NOT_CHANNEL);
                    case MISSING -> throw new TrackingException(Code.NOT_FOUND);
                };
            }
            Preview p = preview.get();
            if (newest == null) {
                // a page of fewer than 10 posts is the whole channel
                if (p.seen().size() >= 10) p = withOlder(username, p, BACKFILL_PAGES - 1, seen -> seen.size() >= BACKFILL_POSTS);
            } else {
                int back = Math.min(newest, upTo == null ? newest : upTo);
                p = withOlder(username, p, GAP_PAGES, seen -> Collections.min(seen) <= back);
            }
            return p;
        } catch (IOException e) {
            log.info("Could not fetch the preview of @{}: {}", username, e.getMessage());
            throw new TrackingException(Code.UNREACHABLE, e);
        }
    }

    /**
     * Adds older pages ({@code ?before=}) to a preview, at most {@code pages} of them, until {@code enough} holds for the
     * ids seen so far. The pages follow each other, so together they still cover one unbroken range of messages.
     */
    private Preview withOlder(String username, Preview p, int pages, Predicate<List<Integer>> enough) {
        List<PreviewPost> all = new ArrayList<>(p.posts());
        List<Integer> seen = new ArrayList<>(p.seen());
        for (int i = 0; i < pages && !seen.isEmpty() && !enough.test(seen); i++) {
            int oldest = Collections.min(seen);
            Optional<Preview> older;
            try {
                older = PreviewParser.preview(client.preview(username, oldest).html(), username);
            } catch (IOException e) {
                log.debug("Older posts of @{} unavailable: {}", username, e.getMessage());
                break;
            }
            // nothing older, or a page that ignored ?before=
            if (older.isEmpty() || older.get().seen().isEmpty() || Collections.min(older.get().seen()) >= oldest) break;
            all.addAll(0, older.get().posts().stream().filter(o -> o.messageId() < oldest).toList());
            seen.addAll(older.get().seen().stream().filter(id -> id < oldest).toList());
        }
        return new Preview(p.username(), p.title(), p.description(), p.photoUrl(), p.subscribers(), p.verified(), p.counters(), all, seen);
    }

    /**
     * Saves the posts on the preview: new ones are added, the others updated (views, reactions, an edited text). A
     * stored post within the range the preview covers that isn't on it any more was deleted in the channel.
     */
    private void store(TrackedChannel c, Preview preview) {
        if (preview.seen().isEmpty()) return;
        List<PreviewPost> fresh = preview.posts();
        Map<Integer, PreviewPost> byId = new LinkedHashMap<>();
        for (PreviewPost p : fresh) byId.put(p.messageId(), p);
        Set<Integer> seen = new HashSet<>(preview.seen());
        int from = Collections.min(preview.seen()), to = Collections.max(preview.seen());
        tx.executeWithoutResult(s -> {
            List<TrackedPost> gone = posts.findAllByTrackedChannelIdAndMessageIdBetween(c.getId(), from, to).stream()
                    .filter(t -> !seen.contains(t.getMessageId())).toList();
            if (!gone.isEmpty()) posts.deleteAll(gone);
            Map<Integer, TrackedPost> known = posts.findAllByTrackedChannelIdAndMessageIdIn(c.getId(), byId.keySet()).stream()
                    .collect(Collectors.toMap(TrackedPost::getMessageId, Function.identity()));
            Instant now = Instant.now();
            List<TrackedPost> batch = new ArrayList<>();
            for (PreviewPost p : byId.values()) {
                TrackedPost t = known.getOrDefault(p.messageId(), new TrackedPost());
                t.setTrackedChannelId(c.getId());
                t.setMessageId(p.messageId());
                t.setPostedAt(p.postedAt());
                t.setKind(p.kind());
                t.setBody(trunc(p.text(), 8192));
                if (p.views() != null) t.setViews(p.views());
                if (p.views() != null && t.getViews24h() == null && dayOld(p.postedAt(), now)) t.setViews24h(p.views());
                t.setReactions(p.reactions());
                t.setReactionBreakdown(p.reactionBreakdown());
                t.setMediaUrl(trunc(p.mediaUrl(), 1024));
                t.setMedia(FeedPost.media(p.media()));
                t.setHasLink(p.hasLink());
                t.setForwardedFrom(trunc(p.forwardedFrom(), 255));
                t.setEdited(p.edited());
                t.setUpdatedAt(now);
                batch.add(t);
            }
            posts.saveAll(batch);
        });
    }

    /** Whether a reading now catches a post at about a day old (24–48 hours), the age its views are compared at. */
    static boolean dayOld(Instant postedAt, Instant now) {
        Duration age = Duration.between(postedAt, now);
        return age.compareTo(Duration.ofHours(24)) >= 0 && age.compareTo(Duration.ofHours(48)) < 0;
    }

    /** Today's row: subscribers and average views as of this refresh. */
    void snapshot(TrackedChannel c) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Integer avg = TrackedStats.of(c.getSubscribers(), posts(c), List.of(), Instant.now(), ZoneOffset.UTC).avgViews();
        TrackedSnapshot s = snapshots.findByTrackedChannelIdAndDay(c.getId(), today).orElseGet(() -> new TrackedSnapshot(c.getId(), today));
        if (c.getSubscribers() != null) s.setSubscribers(c.getSubscribers());
        if (avg != null) s.setAvgViews(avg);
        try {
            snapshots.save(s);
        } catch (org.springframework.dao.DataIntegrityViolationException raced) {
            // two refreshes of the same channel on the same day; one row is enough
        }
    }

    private static String trunc(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
