package com.halty.gramalyzerbot.bot.screen;

import com.halty.gramalyzerbot.bot.ui.*;
import com.halty.gramalyzerbot.i18n.Tr;
import com.halty.gramalyzerbot.tracking.TrackedChannel;
import com.halty.gramalyzerbot.tracking.TrackedPost;
import com.halty.gramalyzerbot.tracking.TrackedStats;
import com.halty.gramalyzerbot.tracking.TrackingException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/** The watchlist: public channels someone keeps an eye on without the bot being in them. */
@Component
public class WatchScreens {

    private static final String[] MEDALS = {"🥇", "🥈", "🥉", "4️⃣", "5️⃣"};

    /** @param rows each channel with its latest stats, in the order they were added */
    public View list(List<Row> rows) {
        View v = View.of(Tr.t("watch.title")).line(Tr.t("watch.subtitle")).blank();
        if (rows.isEmpty()) v.line(Tr.t("watch.empty"));
        for (Row r : rows) {
            TrackedChannel c = r.channel();
            String subs = c.getSubscribers() == null ? "" : " · 👥 " + Fmt.compact(c.getSubscribers());
            String views = r.stats().avgViews() == null ? "" : " · 👁 " + Fmt.compact(r.stats().avgViews());
            String warn = c.getLastError() != null ? "⚠️ " : "📡 ";
            v.row(Btn.cb(warn + Fmt.trunc(c.getTitle(), 26) + subs + views, Cb.of("wl:show", c.getId())));
        }
        v.row(Btn.cb(Tr.t("watch.btn.add"), "wl:add"));
        return v.row(Btn.cb(Tr.t("btn.back"), "nav:active"));
    }

    public record Row(TrackedChannel channel, TrackedStats stats) {}

    public View addPrompt() {
        return View.of(Tr.t("watch.add.title")).blank()
                .line(Tr.t("watch.add.hint"))
                .line(Tr.t("watch.add.example"))
                .row(Btn.cb(Tr.t("btn.cancel"), "wl:list"));
    }

    public View card(TrackedChannel c, TrackedStats s, boolean watching) { return card(c, s, watching, null); }

    /** A channel's card: who it is, how big it is and how its posts do. {@code note} goes on top, e.g. "Added". */
    public View card(TrackedChannel c, TrackedStats s, boolean watching, String note) {
        Instant now = Instant.now();
        View v = new View();
        if (note != null) v.line(note).blank();
        v.line("📡 <b>" + Fmt.esc(c.getTitle()) + "</b>" + (c.isVerified() ? " ☑️" : "") + (c.isDemo() ? "" : "  <i>@" + Fmt.esc(c.getUsername()) + "</i>"));
        if (c.getDescription() != null && !c.getDescription().isBlank())
            v.line("<i>" + Fmt.esc(Fmt.trunc(c.getDescription(), 180)) + "</i>");
        v.line(Fmt.rule());
        if (c.getSubscribers() != null) {
            String delta = s.change7() == null ? "" : "   " + (s.change7() >= 0 ? "▲ " : "▼ ") + Fmt.signed(s.change7()) + " (" + Labels.windowShort(7) + ")";
            v.line(Tr.t("home.subscribers", Fmt.num(c.getSubscribers())) + delta);
        }
        if (s.avgViews() != null) {
            String er = s.er() == null ? "" : " · " + Tr.t("watch.er", Fmt.one(s.er()) + "%");
            v.line(Tr.t("watch.avg_views", Fmt.num(s.avgViews())) + er);
        }
        if (s.reactionRate() != null) v.line(Tr.t("watch.reaction_rate", Fmt.one(s.reactionRate())));
        if (s.postsPerDay() != null)
            v.line(Tr.t("watch.per_day", Fmt.one(s.postsPerDay())) + (s.lastPost() == null ? "" : " · " + Tr.t("watch.last_post", Fmt.ago(s.lastPost(), now))));
        if (!s.kinds().isEmpty()) {
            int total = s.kinds().stream().mapToInt(TrackedStats.Share::posts).sum();
            List<String> mix = s.kinds().stream().limit(3)
                    .map(k -> Labels.kind(k.kind()) + " " + Math.round(k.posts() * 100.0 / total) + "%").toList();
            v.line(Tr.t("watch.mix", String.join(" · ", mix)));
        }
        List<Integer> busiest = IntStream.range(0, 24).boxed().filter(h -> s.hours()[h] > 0)
                .sorted(Comparator.comparingInt((Integer h) -> s.hours()[h]).reversed()).limit(2).toList();
        if (!busiest.isEmpty())
            v.line(Tr.t("watch.active_hours", String.join(", ", busiest.stream().map(Fmt::hourRange).toList())));
        if (!s.hashtags().isEmpty()) {
            List<String> tags = s.hashtags().stream().limit(4).map(t -> Labels.topic(t.tag()) + " (" + t.posts() + ")").toList();
            v.line(Tr.t("watch.hashtags", String.join(" · ", tags)));
        }
        List<Integer> series = s.series().stream().map(TrackedStats.Point::subscribers).filter(java.util.Objects::nonNull).toList();
        if (series.size() >= 2) v.line("<pre>" + Fmt.sparkline(InsightScreens.downsample(series, 30)) + "</pre>");
        if (s.avgViews() == null && c.getSubscribers() != null)
            v.blank().line(Tr.t("watch.no_posts"));
        v.blank().line("<i>" + Tr.t("watch.updated", Fmt.ago(c.getFetchedAt(), now)) + (s.sample() > 0 ? " · " + Tr.n("home.based_on", s.sample()) : "") + "</i>");
        if (c.getLastError() != null) v.line(Tr.t("watch.stale", error(c.getLastError())));

        long id = c.getId();
        v.row(Btn.cb(Tr.t("btn.top"), Cb.of("wl:top", id)), Btn.cb(Tr.t("btn.refresh"), Cb.of("wl:ref", id)));
        if (!c.isDemo()) v.row(Btn.link(Tr.t("watch.btn.open"), "https://t.me/" + c.getUsername()));
        v.row(watching ? Btn.cb(Tr.t("watch.btn.remove"), Cb.of("wl:rm", id)) : Btn.cb(Tr.t("watch.btn.track"), Cb.of("wl:addid", id)));
        return v.row(Btn.cb(Tr.t("watch.btn.back"), "wl:list"));
    }

    public View top(TrackedChannel c, TrackedStats s) {
        View v = View.of(Tr.t("top.title")).line("<i>" + Fmt.esc(c.getTitle()) + "</i>").blank();
        if (s.top().isEmpty()) v.line(Tr.t("watch.top_empty"));
        List<Btn> links = new ArrayList<>();
        for (int i = 0; i < s.top().size(); i++) {
            TrackedPost p = s.top().get(i);
            String body = p.getBody() == null || p.getBody().isBlank() ? Labels.kind(p.getKind()) : Labels.kindEmoji(p.getKind()) + " " + Fmt.trunc(p.getBody(), 70);
            v.line(MEDALS[i] + " " + Fmt.esc(body));
            v.line("     👁 " + Fmt.num(p.getViews()) + " · ❤ " + Fmt.num(p.getReactions()) + " · " + InsightScreens.when(p.getPostedAt().atZone(ZoneOffset.UTC)) + " UTC");
            if (!c.isDemo()) links.add(Btn.link("↗ " + (i + 1), "https://t.me/" + c.getUsername() + "/" + p.getMessageId()));
        }
        if (!links.isEmpty()) v.row(links.toArray(new Btn[0]));
        return v.row(Btn.cb(Tr.t("btn.back"), Cb.of("wl:show", c.getId())));
    }

    /** The reason a channel couldn't be fetched, in the current language. */
    public static String error(TrackingException e) { return error(e.code().key()); }

    public static String error(String key) {
        String k = "watch.err." + key;
        return Tr.has(k) ? Tr.t(k) : Tr.t("watch.err.unreachable");
    }
}
