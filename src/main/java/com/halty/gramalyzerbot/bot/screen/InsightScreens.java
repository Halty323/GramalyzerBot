package com.halty.gramalyzerbot.bot.screen;

import com.halty.gramalyzerbot.analytics.AnalysisEngine;
import com.halty.gramalyzerbot.analytics.Report;
import com.halty.gramalyzerbot.analytics.Report.Group;
import com.halty.gramalyzerbot.analytics.Report.Scored;
import com.halty.gramalyzerbot.analytics.Report.Slot;
import com.halty.gramalyzerbot.analytics.Sample;
import com.halty.gramalyzerbot.bot.ui.*;
import com.halty.gramalyzerbot.channel.*;
import com.halty.gramalyzerbot.post.AnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** The analysis screens: best times, what to write, top posts and growth. */
@Component
@RequiredArgsConstructor
public class InsightScreens {

    private static final int PAGE = 5;
    private static final int MAX_TOP = 20;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH);
    private static final String[] MEDALS = {"🥇", "🥈", "🥉", "4️⃣", "5️⃣"};
    private static final int[] WINDOWS = {30, 90, 180, 0};

    private final AnalysisService analysis;
    private final SubscriberService subscribers;

    // ------------------------------------------------------------------ best times

    public View times(Channel ch, int win, String mode) {
        Report r = analysis.report(ch, win);
        View v = header("⏰ <b>Best times to post</b>", ch, win, true);
        View gate = gate(v, r, ch);
        if (gate != null) return withChips(gate, ch, win, "nav:times", mode);

        switch (mode) {
            case "days" -> daysBody(v, r);
            case "grid" -> gridBody(v, r);
            default -> mainBody(v, r);
        }
        v.blank().line("<i>" + Labels.confidence(r.confidence()) + " · " + r.eligible() + " posts · lift = × your typical engagement</i>");
        if (r.notes().contains(Report.Note.LOW_TIME_VARIETY))
            v.line("💡 <i>You almost always post at the same hours, so I can't tell whether other hours would do better. Try the 🧪 ideas.</i>");

        long id = ch.getId();
        List<Btn> modes = new ArrayList<>();
        if (!mode.equals("main")) modes.add(Btn.cb("⏰ Top slots", Cb.of("nav:times", id, win, "main")));
        if (!mode.equals("days")) modes.add(Btn.cb("📅 By weekday", Cb.of("nav:times", id, win, "days")));
        if (!mode.equals("grid")) modes.add(Btn.cb("🌡 Heatmap", Cb.of("nav:times", id, win, "grid")));
        v.row(modes.toArray(new Btn[0]));
        return withChips(v, ch, win, "nav:times", mode);
    }

    private void mainBody(View v, Report r) {
        v.line("<b>Your strongest slots</b>");
        if (r.topSlots().isEmpty()) {
            v.line("I don't have enough posts around any single time yet. Keep posting, or import your history.");
        }
        int n = Math.min(MEDALS.length, r.topSlots().size());
        for (int i = 0; i < n; i++) {
            Slot s = r.topSlots().get(i);
            v.line(MEDALS[i] + " <b>" + Labels.slot(s) + "</b> – " + Fmt.lift(s.lift()) + " · " + s.support() + (s.support() == 1 ? " post" : " posts") + " nearby");
        }
        if (!r.experiments().isEmpty()) {
            v.blank().line("<b>🧪 Worth testing</b>");
            for (Slot s : r.experiments())
                v.line("• " + Labels.slot(s) + " – neighbouring times look strong, but you rarely post here");
        }
        v.blank().line("<b>Days at a glance</b>");
        v.raw("<pre>");
        double max = r.days().stream().mapToDouble(Report.DayStat::lift).max().orElse(1);
        for (Report.DayStat d : r.days()) {
            v.raw(String.format(Locale.ROOT, "%s %-8s %4s (%d)\n", Fmt.dayShort(d.day()), Fmt.bar(d.lift(), max, 8), Fmt.lift(d.lift()), d.posts()));
        }
        v.line("</pre>");
    }

    private void daysBody(View v, Report r) {
        v.line("<b>Best time on each day</b>");
        for (java.time.DayOfWeek d : java.time.DayOfWeek.values()) {
            List<Slot> best = r.bestPerDay().getOrDefault(d, List.of());
            String slots = best.isEmpty() ? "<i>no clear winner</i>"
                    : String.join(" · ", best.stream().map(s -> "<b>" + Fmt.hour(s.hour()) + "</b> (" + Fmt.lift(s.lift()) + ")").toList());
            v.line(Fmt.dayLong(d) + ": " + slots);
        }
        if (!r.experiments().isEmpty()) {
            v.blank().line("<b>🧪 Worth testing</b>");
            for (Slot s : r.experiments()) v.line("• " + Labels.slot(s) + " (predicted " + Fmt.lift(s.lift()) + ")");
        }
    }

    private void gridBody(View v, Report r) {
        v.line("<b>Week at a glance</b> <i>(2-hour blocks)</i>");
        v.line("<pre>" + Heatmap.render(r.grid(), r.gridPosts()) + "</pre>");
        v.line("<i>" + Heatmap.LEGEND + "</i>");
        List<Report.HourStat> hours = r.hours().stream().filter(h -> h.posts() >= 3)
                .sorted(Comparator.comparingDouble(Report.HourStat::lift).reversed()).limit(3).toList();
        if (!hours.isEmpty()) {
            v.blank().line("<b>Best hours, any day</b>");
            for (Report.HourStat h : hours) v.line("• " + Fmt.hourRange(h.hour()) + " – " + Fmt.lift(h.lift()) + " · " + h.posts() + " posts");
        }
    }

    // ------------------------------------------------------------------ what to write

    private record Item(String label, double lift, int posts) {}

    public View content(Channel ch, int win, String mode) {
        Report r = analysis.report(ch, win);
        View v = header("💡 <b>What to write about</b>", ch, win, false);
        View gate = gate(v, r, ch);
        if (gate != null) return withChips(gate, ch, win, "nav:content", mode);

        switch (mode) {
            case "formats" -> formatsBody(v, r);
            case "topics" -> topicsBody(v, r);
            default -> contentMain(v, r);
        }
        v.blank().line("<i>" + Labels.confidence(r.confidence()) + " · " + r.eligible() + " posts · engagement = " + ch.getMetric().formula() + "</i>");

        long id = ch.getId();
        List<Btn> modes = new ArrayList<>();
        if (!mode.equals("main")) modes.add(Btn.cb("💡 Summary", Cb.of("nav:content", id, win, "main")));
        if (!mode.equals("formats")) modes.add(Btn.cb("🎞 Formats", Cb.of("nav:content", id, win, "formats")));
        if (!mode.equals("topics")) modes.add(Btn.cb("🏷 Topics & words", Cb.of("nav:content", id, win, "topics")));
        v.row(modes.toArray(new Btn[0]));
        return withChips(v, ch, win, "nav:content", mode);
    }

    private void contentMain(View v, Report r) {
        List<Item> items = new ArrayList<>();
        for (Group g : r.topics()) items.add(new Item(Labels.topic(g.key()), g.lift(), g.posts()));
        for (Group g : r.kinds()) items.add(new Item(Labels.kind(g.key()) + " posts", g.lift(), g.posts()));
        for (Group g : r.lengths()) items.add(new Item("Length: " + Labels.length(g.key()), g.lift(), g.posts()));
        items.sort(Comparator.comparingDouble(Item::lift).reversed());
        List<Item> words = new ArrayList<>();
        for (Group g : r.keywords()) words.add(new Item("“" + Fmt.esc(g.key()) + "”", g.lift(), g.posts()));

        // keywords are noisier than topics/formats, so the summary only shows the strongest few
        List<Item> up = new ArrayList<>(items.stream().filter(i -> i.lift() >= 1.1).toList());
        up.addAll(words.stream().filter(i -> i.lift() >= 1.1).limit(3).toList());
        up.sort(Comparator.comparingDouble(Item::lift).reversed());
        if (up.size() > 6) up = up.subList(0, 6);
        List<Item> down = new ArrayList<>(items.stream().filter(i -> i.lift() <= 0.85).toList());
        down.addAll(words.stream().filter(i -> i.lift() <= 0.85).sorted(Comparator.comparingDouble(Item::lift)).limit(2).toList());
        down.sort(Comparator.comparingDouble(Item::lift));
        if (down.size() > 4) down = down.subList(0, 4);

        if (up.isEmpty() && down.isEmpty()) {
            v.line("Everything performs about the same so far – no clear winners or losers. That usually changes with more posts.");
        }
        if (!up.isEmpty()) {
            v.line("<b>🔥 Double down on</b>");
            for (Item i : up) v.line("▲ " + i.label() + " – " + Fmt.lift(i.lift()) + " · " + i.posts() + " posts");
        }
        if (!down.isEmpty()) {
            v.blank().line("<b>🧊 Cooling off</b>");
            for (Item i : down) v.line("▼ " + i.label() + " – " + Fmt.lift(i.lift()) + " · " + i.posts() + " posts");
        }
        String idea = idea(r);
        if (idea != null) v.blank().line("<b>✍️ Next post idea</b>").line("<blockquote>" + idea + "</blockquote>");
    }

    /** A concrete recipe stitched from the strongest format, topic, length and slot. */
    static String idea(Report r) {
        List<String> parts = new ArrayList<>();
        r.kinds().stream().filter(g -> g.lift() >= 1.1).findFirst().ifPresent(g -> parts.add("a <b>" + Labels.kind(g.key()).substring(Labels.kind(g.key()).indexOf(' ') + 1).toLowerCase(Locale.ROOT) + "</b>"));
        r.topics().stream().filter(g -> g.lift() >= 1.1).findFirst().ifPresent(g -> parts.add("about <b>" + Labels.topic(g.key()) + "</b>"));
        r.lengths().stream().filter(g -> g.lift() >= 1.1 && !g.key().equals("NONE")).findFirst().ifPresent(g -> parts.add("<b>" + Labels.length(g.key()).toLowerCase(Locale.ROOT) + "</b>"));
        if (parts.isEmpty()) return null;
        String when = r.topSlots().isEmpty() ? "" : ", published <b>" + Labels.slot(r.topSlots().get(0)) + "</b>";
        return "Try " + String.join(", ", parts) + when + ".";
    }

    private void formatsBody(View v, Report r) {
        boolean any = section(v, false, "🎞 Formats", r.kinds(), Labels::kind);
        any |= section(v, any, "📏 Length", r.lengths(), Labels::length);
        any |= section(v, any, "🔗 Links", r.links(), Labels::link);
        any |= section(v, any, "🔁 Posts per day", r.frequency(), Labels::frequency);
        if (!any) v.line("Not enough variety yet to compare formats.");
    }

    private void topicsBody(View v, Report r) {
        if (r.topics().isEmpty()) {
            v.line("<b>🏷 Topics</b>").line("I didn't find hashtags used often enough. Use a few consistent hashtags – or let a moderator tag posts – and topics will show up here.");
        } else {
            section(v, false, "🏷 Topics", r.topics().size() > 12 ? r.topics().subList(0, 12) : r.topics(), Labels::topic);
        }
        List<Group> kws = r.keywords();
        if (kws.isEmpty()) {
            v.blank().line("<b>🔤 Words</b>");
            if (r.eligible() < AnalysisEngine.MIN_POSTS_FOR_KEYWORDS)
                v.line("<i>Word analysis starts at " + AnalysisEngine.MIN_POSTS_FOR_KEYWORDS + " posts – " + r.eligible() + " so far.</i>");
            else
                v.line("<i>No word stands out yet: none shows up in enough posts (" + AnalysisEngine.minKeywordPosts(r.eligible())
                        + "+) with a clearly different response. Posts with more text, or more posts, will bring them out.</i>");
        } else {
            List<Group> win = kws.stream().filter(g -> g.lift() > 1).limit(8).toList();
            List<Group> lose = kws.stream().filter(g -> g.lift() < 1).sorted(Comparator.comparingDouble(Group::lift)).limit(5).toList();
            v.blank().line("<b>🔤 Words that travel well</b>");
            for (Group g : win) v.line("▲ “" + Fmt.esc(g.key()) + "” – " + Fmt.lift(g.lift()) + " · " + g.posts());
            if (!lose.isEmpty()) {
                v.blank().line("<b>🔤 Words that fall flat</b>");
                for (Group g : lose) v.line("▼ “" + Fmt.esc(g.key()) + "” – " + Fmt.lift(g.lift()) + " · " + g.posts());
            }
        }
    }

    /** Writes a titled list of groups; returns true if anything was written. */
    private static boolean section(View v, boolean blankBefore, String title, List<Group> groups,
                                   java.util.function.Function<String, String> label) {
        if (groups.isEmpty()) return false;
        if (blankBefore) v.blank();
        v.line("<b>" + title + "</b>");
        for (Group g : groups) v.line("• " + label.apply(g.key()) + " – " + Fmt.lift(g.lift()) + " · " + g.posts() + " posts");
        return true;
    }

    // ------------------------------------------------------------------ top posts

    public View top(Channel ch, Role role, int page) {
        Report r = analysis.report(ch);
        List<Scored> all = r.topPosts().size() > MAX_TOP ? r.topPosts().subList(0, MAX_TOP) : r.topPosts();
        View v = new View().line("🏆 <b>Top posts</b>").line("<i>" + Fmt.esc(ch.getTitle()) + " · " + Labels.window(ch.getWindowDays()) + "</i>").blank();
        long id = ch.getId();
        if (all.isEmpty()) {
            v.line("Nothing to rank yet – posts appear here once they're a day old.");
            return v.row(Btn.cb("‹ Dashboard", Cb.of("nav:home", id)));
        }
        int pages = (all.size() + PAGE - 1) / PAGE;
        page = Math.max(0, Math.min(page, pages - 1));
        boolean moderate = role.can(Permission.MODERATE_POSTS);
        for (int i = page * PAGE; i < Math.min(all.size(), (page + 1) * PAGE); i++) {
            Sample s = all.get(i).sample();
            String title = s.text().isBlank() ? Labels.kind(s.kind().name()) : Labels.kindEmoji(s.kind().name()) + " " + Fmt.trunc(s.text(), 70);
            String comments = s.commentsKnown() && s.comments() > 0 ? " · 💭 " + s.comments() : "";
            v.line("<b>" + (i + 1) + ".</b> " + Fmt.esc(title));
            v.line("     ❤ " + s.reactions() + comments + " · " + WHEN.format(s.postedAt().atZone(ch.zone())));
            List<Btn> row = new ArrayList<>();
            String url = Links.post(ch.getTelegramChatId(), ch.getUsername(), ch.isDemo(), s.messageId());
            if (url != null) row.add(Btn.link("↗ " + (i + 1), url));
            if (moderate) {
                row.add(Btn.cb("🏷 " + (i + 1), Cb.of("pm:topic", id, s.id())));
                row.add(Btn.cb("🚫 " + (i + 1), Cb.of("pm:excl", id, s.id(), page)));
            }
            v.row(row.toArray(new Btn[0]));
        }
        if (moderate) v.blank().line("<i>🏷 label a post with a topic · 🚫 leave it out of the analysis (ads, giveaways…)</i>");
        List<Btn> nav = new ArrayList<>();
        if (page > 0) nav.add(Btn.cb("‹ Prev", Cb.of("nav:top", id, page - 1)));
        nav.add(Btn.cb((page + 1) + " / " + pages, Cb.of("nav:top", id, page)));
        if (page < pages - 1) nav.add(Btn.cb("Next ›", Cb.of("nav:top", id, page + 1)));
        v.row(nav.toArray(new Btn[0]));
        if (moderate) v.row(Btn.cb("🚫 Excluded posts", Cb.of("nav:excluded", id)));
        v.row(Btn.cb("‹ Dashboard", Cb.of("nav:home", id)));
        return v;
    }

    public View excluded(Channel ch, Role role) {
        List<Sample> ex = analysis.samples(ch).stream().filter(Sample::excluded)
                .sorted(Comparator.comparing(Sample::postedAt).reversed()).limit(10).toList();
        View v = new View().line("🚫 <b>Excluded posts</b>").line("<i>These are ignored by every analysis.</i>").blank();
        long id = ch.getId();
        if (ex.isEmpty()) v.line("Nothing excluded.");
        for (Sample s : ex) {
            v.line("• " + Fmt.esc(Fmt.trunc(s.text().isBlank() ? Labels.kind(s.kind().name()) : s.text(), 60)) + " · ❤ " + s.reactions());
            v.rowIf(role.can(Permission.MODERATE_POSTS), Btn.cb("↩ Restore: " + Fmt.trunc(s.text().isBlank() ? "post" : s.text(), 18), Cb.of("pm:excl", id, s.id(), "x")));
        }
        return v.row(Btn.cb("‹ Top posts", Cb.of("nav:top", id, 0)));
    }

    // ------------------------------------------------------------------ growth

    public View growth(Channel ch, int days) {
        SubscriberService.Growth g = subscribers.growth(ch, days);
        View v = new View().line("📈 <b>Subscriber growth</b>").line("<i>" + Fmt.esc(ch.getTitle()) + " · " + Labels.window(days) + "</i>").blank();
        if (g.current() != null) v.line("👥 <b>" + Fmt.num(g.current()) + "</b> subscribers now");
        if (g.series().size() < 2 || g.change() == null) {
            v.line("I need a few days of snapshots to draw a trend – I check your subscriber count regularly.");
        } else {
            double perDay = g.change() / (double) days;
            v.line((g.change() >= 0 ? "▲ " : "▼ ") + "<b>" + Fmt.signed(g.change()) + "</b> in " + Labels.window(days) + " (≈ " + (perDay >= 0 ? "+" : "") + Fmt.one(perDay) + "/day)");
            v.line("<pre>" + Fmt.sparkline(downsample(g.series(), 40)) + "</pre>");
            v.line("<i>" + Fmt.num(g.series().get(0)) + " → " + Fmt.num(g.series().get(g.series().size() - 1)) + "</i>");
        }
        long id = ch.getId();
        List<Btn> chips = new ArrayList<>();
        for (int d : new int[]{30, 90, 180}) chips.add(Btn.cb((d == days ? "✓ " : "") + d + "d", Cb.of("nav:growth", id, d)));
        v.row(chips.toArray(new Btn[0]));
        return v.row(Btn.cb("‹ Dashboard", Cb.of("nav:home", id)));
    }

    static List<Integer> downsample(List<Integer> in, int max) {
        if (in.size() <= max) return in;
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < max; i++) out.add(in.get((int) Math.round(i * (in.size() - 1) / (double) (max - 1))));
        return out;
    }

    // ------------------------------------------------------------------ shared

    private View header(String title, Channel ch, int win, boolean showZone) {
        String zone = showZone ? " · " + Fmt.esc(TimeZones.label(ch.zone(), Instant.now())) : "";
        return new View().line(title).line("<i>" + Fmt.esc(ch.getTitle()) + " · " + Labels.window(win) + zone + "</i>").blank();
    }

    /** Returns a finished "not enough data" view (with back button), or null when the report is usable. */
    private View gate(View v, Report r, Channel ch) {
        if (r.eligible() > 0 && !r.hasSignal()) {
            v.line("🤔 <b>No reactions or comments to learn from yet.</b>")
                    .line("Make sure reactions are enabled on your channel" + (ch.getDiscussionChatId() == null ? ", and link the discussion group to track comments too." : "."));
        } else if (r.eligible() < AnalysisEngine.MIN_POSTS_FOR_TIMES) {
            v.line("🌱 <b>Not enough posts yet.</b>")
                    .line(Fmt.progress(r.eligible(), AnalysisEngine.MIN_POSTS_FOR_TIMES, 10) + "  " + r.eligible() + " / " + AnalysisEngine.MIN_POSTS_FOR_TIMES)
                    .line("Posts count once they're a day old." + (ch.isHistoryImported() || ch.isDemo() ? "" : " Import your history with /import to skip the wait."));
            if (r.totalInWindow() > r.eligible()) v.line("<i>" + (r.totalInWindow() - r.eligible()) + " more are excluded or still too fresh.</i>");
        } else {
            return null;
        }
        return v;
    }

    /** Adds the window chips and a back button. */
    private View withChips(View v, Channel ch, int win, String screen, String mode) {
        long id = ch.getId();
        List<Btn> chips = new ArrayList<>();
        for (int w : WINDOWS) chips.add(Btn.cb((w == win ? "✓ " : "") + Labels.windowShort(w), Cb.of(screen, id, w, mode)));
        v.row(chips.toArray(new Btn[0]));
        return v.row(Btn.cb("‹ Dashboard", Cb.of("nav:home", id)));
    }
}
