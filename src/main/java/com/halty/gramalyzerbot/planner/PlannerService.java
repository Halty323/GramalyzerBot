package com.halty.gramalyzerbot.planner;

import com.halty.gramalyzerbot.analytics.PostKind;
import com.halty.gramalyzerbot.analytics.Report;
import com.halty.gramalyzerbot.analytics.Sample;
import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.channel.Links;
import com.halty.gramalyzerbot.crm.CrmBridge;
import com.halty.gramalyzerbot.post.AnalysisService;
import com.halty.gramalyzerbot.post.Post;
import com.halty.gramalyzerbot.post.PostRepository;
import com.halty.gramalyzerbot.workspace.Models;
import com.halty.gramalyzerbot.workspace.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

/**
 * Puts the queue on the channel's best times: the next free good slots, drafts spread over them in one go, and the
 * channel's best old posts offered again as evergreen reposts. Slots come from the analysis (the best weekday/hour
 * cells); a slot is free when no queued post is within {@value #GAP_HOURS} hours and the day doesn't already have as
 * many posts as the channel usually publishes in a day.
 */
@Service
@RequiredArgsConstructor
public class PlannerService {

    static final int GAP_HOURS = 3;
    static final int HORIZON_DAYS = 14;
    static final Duration EARLIEST = Duration.ofMinutes(30);
    /** How much a slot's lift is discounted per day ahead when picking the next free slots. */
    static final double SOONER = 0.03;
    /** Posts younger than this are too fresh to repeat, and a repost isn't offered again for as long. */
    static final Duration EVERGREEN_AGE = Duration.ofDays(90);
    static final double EVERGREEN_LIFT = 1.3;
    static final int EVERGREEN_SHOWN = 6;
    private static final int[] FALLBACK_HOURS = {9, 13, 18};

    /** @param guess true when the channel has too few posts to know its best times, so the slot is a common default */
    public record Slot(Instant at, double lift, boolean guess) {}

    public record Assignment(String postId, String title, Instant at, double lift) {}

    /** @param queuedAt when a repost of it is already in the queue, or null */
    public record Evergreen(int messageId, Instant postedAt, String kind, String preview, String url, double lift, Instant queuedAt) {}

    private final AnalysisService analysis;
    private final PostRepository posts;
    private final WorkspaceService workspace;
    private final CrmBridge bridge;

    // ------------------------------------------------------------------ draft score

    public DraftScore.Result score(Channel ch, DraftScore.Draft d) {
        return DraftScore.score(analysis.report(ch), d, ch.zone());
    }

    /** The PostKind a queued post goes out as. */
    public static String kindOf(List<String> mediaKinds) {
        if (mediaKinds == null || mediaKinds.isEmpty()) return PostKind.TEXT.name();
        if (mediaKinds.size() > 1) return PostKind.ALBUM.name();
        return switch (mediaKinds.getFirst()) { case "video" -> "VIDEO"; case "animation" -> "ANIMATION"; default -> "PHOTO"; };
    }

    // ------------------------------------------------------------------ slots

    /** The website channel behind a bot channel, registering it there if needed; empty when that isn't possible. */
    public Optional<String> crmId(Channel ch) {
        if (ch.getWorkspaceChannelId() != null) return Optional.of(ch.getWorkspaceChannelId());
        return Optional.ofNullable(bridge.ensureInCrm(ch).getWorkspaceChannelId());
    }

    /** Queued posts' times, but not {@code except}'s (the one being edited). */
    List<Instant> taken(String crmId, String except) {
        if (crmId == null) return List.of();
        return workspace.workspace().posts().stream()
                .filter(p -> p.channelId().equals(crmId) && "scheduled".equals(p.status()) && !p.id().equals(except)
                        && !"sent".equals(p.deliveryState()) && !"failed".equals(p.deliveryState()))
                .map(Models.Post::scheduledAt).toList();
    }

    public List<Slot> free(Channel ch, String crmId, String except, int count) {
        return free(analysis.report(ch), ch.zone(), taken(crmId, except), Instant.now(), count);
    }

    static List<Slot> free(Report r, ZoneId zone, List<Instant> taken, Instant now, int count) {
        Map<String, Double> cells = new LinkedHashMap<>();
        boolean guess = !r.hasSignal() || r.topSlots().isEmpty();
        if (guess) {
            for (DayOfWeek d : DayOfWeek.values()) for (int h : FALLBACK_HOURS) cells.put(d + " " + h, 1.0);
        } else {
            for (Report.Slot s : r.topSlots()) cells.put(s.day() + " " + s.hour(), s.lift());
            r.bestPerDay().values().forEach(list -> list.forEach(s -> cells.putIfAbsent(s.day() + " " + s.hour(), s.lift())));
        }
        List<Slot> candidates = new ArrayList<>();
        LocalDate today = now.atZone(zone).toLocalDate();
        for (int i = 0; i <= HORIZON_DAYS; i++) {
            LocalDate date = today.plusDays(i);
            for (var e : cells.entrySet()) {
                String[] dh = e.getKey().split(" ");
                if (DayOfWeek.valueOf(dh[0]) != date.getDayOfWeek()) continue;
                Instant at = date.atTime(Integer.parseInt(dh[1]), 0).atZone(zone).toInstant();
                if (at.isAfter(now.plus(EARLIEST))) candidates.add(new Slot(at, round(e.getValue()), guess));
            }
        }
        // A slow week's best cell shouldn't beat a nearly-as-good one tomorrow, so later slots count a little less.
        candidates.sort(Comparator.comparingDouble((Slot s) -> -s.lift() / (1 + SOONER * Duration.between(now, s.at()).toHours() / 24.0))
                .thenComparing(Slot::at));
        int perDay = Math.max(1, Math.min(3, (int) Math.ceil(r.cadence().postsPerWeek() / 7)));
        List<Instant> busy = new ArrayList<>(taken);
        List<Slot> out = new ArrayList<>();
        for (Slot s : candidates) {
            if (out.size() == count) break;
            LocalDate day = s.at().atZone(zone).toLocalDate();
            boolean near = busy.stream().anyMatch(t -> Math.abs(Duration.between(t, s.at()).toMinutes()) < GAP_HOURS * 60);
            long sameDay = busy.stream().filter(t -> t.atZone(zone).toLocalDate().equals(day)).count();
            if (near || sameDay >= perDay) continue;
            busy.add(s.at());
            out.add(s);
        }
        out.sort(Comparator.comparing(Slot::at));
        return out;
    }

    /**
     * Spreads drafts (the given ones, or all of the channel's drafts, oldest first) over the next free good slots, the
     * best slot to the oldest draft. With {@code apply} they are scheduled; otherwise it's only a preview.
     */
    public List<Assignment> fill(Channel ch, String crmId, List<String> postIds, boolean apply) {
        List<Models.Post> drafts = new ArrayList<>(workspace.workspace().posts().stream()
                .filter(p -> p.channelId().equals(crmId) && "draft".equals(p.status()) && (postIds == null || postIds.contains(p.id()))).toList());
        Collections.reverse(drafts); // the workspace lists newest first
        List<Slot> slots = new ArrayList<>(free(ch, crmId, null, drafts.size()));
        slots.sort(Comparator.comparingDouble(Slot::lift).reversed().thenComparing(Slot::at));
        List<Assignment> out = new ArrayList<>();
        for (int i = 0; i < Math.min(drafts.size(), slots.size()); i++) {
            Models.Post p = drafts.get(i);
            Slot s = slots.get(i);
            if (apply) workspace.save(p.id(), new Models.PostInput(p.channelId(), p.title(), p.body(), "scheduled", s.at(), p.category(),
                    p.media(), p.buttons(), p.silent(), p.pin(), p.repostOf()));
            out.add(new Assignment(p.id(), p.title(), s.at(), s.lift()));
        }
        out.sort(Comparator.comparing(Assignment::at));
        return out;
    }

    // ------------------------------------------------------------------ evergreen

    /** The channel's best posts from at least three months ago, by how far they beat its median post. */
    public List<Evergreen> evergreen(Channel ch, String crmId) {
        Instant now = Instant.now();
        List<Sample> all = analysis.samples(ch).stream().filter(s -> !s.excluded() && ch.getMetric().scores(s)).toList();
        if (all.size() < DraftScore.MIN_POSTS) return List.of();
        double[] scores = all.stream().mapToDouble(s -> ch.getMetric().score(s)).sorted().toArray();
        double median = scores[scores.length / 2];
        Map<Integer, Instant> queued = new HashMap<>();
        Set<Integer> recent = new HashSet<>();
        for (Models.Post p : crmId == null ? List.<Models.Post>of() : workspace.workspace().posts()) {
            if (!p.channelId().equals(crmId) || !p.repost()) continue;
            if (!"published".equals(p.status())) queued.merge(p.repostOf().getFirst(), p.scheduledAt(), (x, y) -> x.isBefore(y) ? x : y);
            else if (p.scheduledAt().isAfter(now.minus(EVERGREEN_AGE))) recent.add(p.repostOf().getFirst());
        }
        return all.stream()
                .filter(s -> s.postedAt().isBefore(now.minus(EVERGREEN_AGE)) && s.kind() != PostKind.POLL && !recent.contains(s.messageId()))
                .map(s -> new Evergreen(s.messageId(), s.postedAt(), s.kind().name(), preview(s.text()),
                        Links.post(ch.getTelegramChatId(), ch.getUsername(), ch.isDemo(), s.messageId()),
                        round((ch.getMetric().score(s) + 1) / (median + 1)), queued.get(s.messageId())))
                .filter(e -> e.lift() >= EVERGREEN_LIFT)
                .sorted(Comparator.comparingDouble(Evergreen::lift).reversed())
                .limit(EVERGREEN_SHOWN).toList();
    }

    /** Queues a repost of an old post at the next free good slot, sent silently. */
    public Models.Post repost(Channel ch, String crmId, int messageId) {
        if (evergreen(ch, crmId).stream().noneMatch(e -> e.messageId() == messageId && e.queuedAt() == null))
            throw new WorkspaceService.Problem(409, "That post isn't one to repost right now, or it's already in the queue.");
        Post first = posts.findByChannelIdAndMessageId(ch.getId(), messageId)
                .orElseThrow(() -> new WorkspaceService.Problem(404, "That post is no longer known."));
        List<Integer> ids = first.getMediaGroupId() == null ? List.of(messageId)
                : posts.findAllByChannelId(ch.getId()).stream().filter(p -> first.getMediaGroupId().equals(p.getMediaGroupId()))
                        .map(Post::getMessageId).sorted().limit(10).toList();
        Slot slot = free(ch, crmId, null, 1).stream().findFirst()
                .orElseThrow(() -> new WorkspaceService.Problem(409, "No free good slot in the next two weeks. Move or remove a queued post first."));
        String text = analysis.samples(ch).stream().filter(s -> s.messageId() == messageId).map(Sample::text).findFirst().orElse("");
        String title = "🔁 " + (text.isBlank() ? first.getKind().name().toLowerCase(Locale.ROOT) : preview(text, 60));
        return workspace.save(null, new Models.PostInput(crmId, title, text, "scheduled", slot.at(), "Evergreen", null, null, true, null, ids));
    }

    private static String preview(String text) { return preview(text, 120); }

    private static String preview(String text, int max) {
        String s = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        return s.codePointCount(0, s.length()) <= max ? s : s.substring(0, s.offsetByCodePoints(0, max - 1)) + "…";
    }

    private static double round(double x) { return Math.round(x * 100) / 100.0; }
}
