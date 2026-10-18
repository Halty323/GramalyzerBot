package com.halty.gramalyzerbot.bot.screen;

import com.halty.gramalyzerbot.analytics.AnalysisEngine;
import com.halty.gramalyzerbot.analytics.Report;
import com.halty.gramalyzerbot.analytics.Report.Group;
import com.halty.gramalyzerbot.analytics.Report.Scored;
import com.halty.gramalyzerbot.analytics.Report.Slot;
import com.halty.gramalyzerbot.analytics.Sample;
import com.halty.gramalyzerbot.i18n.Tr;
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
    private static final String[] MEDALS = {"🥇", "🥈", "🥉", "4️⃣", "5️⃣"};
    private static final int[] WINDOWS = {30, 90, 180, 0};

    private final AnalysisService analysis;
    private final SubscriberService subscribers;

    // ------------------------------------------------------------------ best times

    public View times(Channel ch, int win, String mode) {
        Report r = analysis.report(ch, win);
        View v = header(Tr.t("times.title"), ch, win, true);
        View gate = gate(v, r, ch);
        if (gate != null) return withChips(gate, ch, win, "nav:times", mode);

        switch (mode) {
            case "days" -> daysBody(v, r);
            case "grid" -> gridBody(v, r);
            default -> mainBody(v, r);
        }
        v.blank().line("<i>" + Labels.confidence(r.confidence()) + " · " + Tr.n("n.posts", r.eligible()) + " · " + Tr.t("times.lift_note") + "</i>");
        if (r.notes().contains(Report.Note.LOW_TIME_VARIETY))
            v.line(Tr.t("times.low_variety"));

        long id = ch.getId();
        List<Btn> modes = new ArrayList<>();
        if (!mode.equals("main")) modes.add(Btn.cb(Tr.t("times.btn.top"), Cb.of("nav:times", id, win, "main")));
        if (!mode.equals("days")) modes.add(Btn.cb(Tr.t("times.btn.days"), Cb.of("nav:times", id, win, "days")));
        if (!mode.equals("grid")) modes.add(Btn.cb(Tr.t("times.btn.grid"), Cb.of("nav:times", id, win, "grid")));
        v.row(modes.toArray(new Btn[0]));
        return withChips(v, ch, win, "nav:times", mode);
    }

    private void mainBody(View v, Report r) {
        v.line(Tr.t("times.strongest"));
        if (r.topSlots().isEmpty()) {
            v.line(Tr.t("times.no_slots"));
        }
        int n = Math.min(MEDALS.length, r.topSlots().size());
        for (int i = 0; i < n; i++) {
            Slot s = r.topSlots().get(i);
            v.line(MEDALS[i] + " <b>" + Labels.slot(s) + "</b> – " + Fmt.lift(s.lift()) + " · " + Tr.n("times.nearby", s.support()));
        }
        if (!r.experiments().isEmpty()) {
            v.blank().line(Tr.t("times.worth_testing"));
            for (Slot s : r.experiments())
                v.line("• " + Labels.slot(s) + " – " + Tr.t("times.experiment"));
        }
        v.blank().line(Tr.t("times.days_glance"));
        v.raw("<pre>");
        double max = r.days().stream().mapToDouble(Report.DayStat::lift).max().orElse(1);
        for (Report.DayStat d : r.days()) {
            v.raw(String.format(Locale.ROOT, "%s %-8s %4s (%d)\n", pad3(Fmt.dayShort(d.day())), Fmt.bar(d.lift(), max, 8), Fmt.lift(d.lift()), d.posts()));
        }
        v.line("</pre>");
    }

    private void daysBody(View v, Report r) {
        v.line(Tr.t("times.each_day"));
        for (java.time.DayOfWeek d : java.time.DayOfWeek.values()) {
            List<Slot> best = r.bestPerDay().getOrDefault(d, List.of());
            String slots = best.isEmpty() ? Tr.t("times.no_winner")
                    : String.join(" · ", best.stream().map(s -> "<b>" + Fmt.hour(s.hour()) + "</b> (" + Fmt.lift(s.lift()) + ")").toList());
            v.line(Fmt.dayLong(d) + ": " + slots);
        }
        if (!r.experiments().isEmpty()) {
            v.blank().line(Tr.t("times.worth_testing"));
            for (Slot s : r.experiments()) v.line("• " + Labels.slot(s) + " (" + Tr.t("times.predicted", Fmt.lift(s.lift())) + ")");
        }
    }

    private void gridBody(View v, Report r) {
        v.line(Tr.t("times.week_glance"));
        v.line("<pre>" + Heatmap.render(r.grid(), r.gridPosts()) + "</pre>");
        v.line("<i>" + Heatmap.legend() + "</i>");
        List<Report.HourStat> hours = r.hours().stream().filter(h -> h.posts() >= 3)
                .sorted(Comparator.comparingDouble(Report.HourStat::lift).reversed()).limit(3).toList();
        if (!hours.isEmpty()) {
            v.blank().line(Tr.t("times.best_hours"));
            for (Report.HourStat h : hours) v.line("• " + Fmt.hourRange(h.hour()) + " – " + Fmt.lift(h.lift()) + " · " + Tr.n("n.posts", h.posts()));
        }
    }

    // ------------------------------------------------------------------ what to write

    private record Item(String label, double lift, int posts) {}

    public View content(Channel ch, int win, String mode) {
        Report r = analysis.report(ch, win);
        View v = header(Tr.t("content.title"), ch, win, false);
        View gate = gate(v, r, ch);
        if (gate != null) return withChips(gate, ch, win, "nav:content", mode);

        switch (mode) {
            case "formats" -> formatsBody(v, r);
            case "topics" -> topicsBody(v, r);
            default -> contentMain(v, r);
        }
        v.blank().line("<i>" + Labels.confidence(r.confidence()) + " · " + Tr.n("n.posts", r.eligible()) + " · " + Tr.t("content.engagement_is", Labels.formula(ch.getMetric())) + "</i>");

        long id = ch.getId();
        List<Btn> modes = new ArrayList<>();
        if (!mode.equals("main")) modes.add(Btn.cb(Tr.t("content.btn.summary"), Cb.of("nav:content", id, win, "main")));
        if (!mode.equals("formats")) modes.add(Btn.cb(Tr.t("content.btn.formats"), Cb.of("nav:content", id, win, "formats")));
        if (!mode.equals("topics")) modes.add(Btn.cb(Tr.t("content.btn.topics"), Cb.of("nav:content", id, win, "topics")));
        v.row(modes.toArray(new Btn[0]));
        return withChips(v, ch, win, "nav:content", mode);
    }

    private void contentMain(View v, Report r) {
        List<Item> items = new ArrayList<>();
        for (Group g : r.topics()) items.add(new Item(Labels.topic(g.key()), g.lift(), g.posts()));
        for (Group g : r.kinds()) items.add(new Item(Tr.t("content.kind_posts", Labels.kind(g.key())), g.lift(), g.posts()));
        for (Group g : r.lengths()) items.add(new Item(Tr.t("content.length_is", Labels.length(g.key())), g.lift(), g.posts()));
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
            v.line(Tr.t("content.all_same"));
        }
        if (!up.isEmpty()) {
            v.line(Tr.t("content.double_down"));
            for (Item i : up) v.line("▲ " + i.label() + " – " + Fmt.lift(i.lift()) + " · " + Tr.n("n.posts", i.posts()));
        }
        if (!down.isEmpty()) {
            v.blank().line(Tr.t("content.cooling"));
            for (Item i : down) v.line("▼ " + i.label() + " – " + Fmt.lift(i.lift()) + " · " + Tr.n("n.posts", i.posts()));
        }
        String idea = idea(r);
        if (idea != null) v.blank().line(Tr.t("content.idea")).line("<blockquote>" + idea + "</blockquote>");
    }

    /** A concrete recipe stitched from the strongest format, topic, length and slot: one line per ingredient. */
    static String idea(Report r) {
        List<String> parts = new ArrayList<>();
        r.kinds().stream().filter(g -> g.lift() >= 1.1).findFirst().ifPresent(g -> parts.add(Tr.t("content.idea.format", Labels.kind(g.key()))));
        r.topics().stream().filter(g -> g.lift() >= 1.1).findFirst().ifPresent(g -> parts.add(Tr.t("content.idea.topic", Labels.topic(g.key()))));
        r.lengths().stream().filter(g -> g.lift() >= 1.1 && !g.key().equals("NONE")).findFirst().ifPresent(g -> parts.add(Tr.t("content.idea.length", Labels.length(g.key()))));
        if (parts.isEmpty()) return null;
        if (!r.topSlots().isEmpty()) parts.add(Tr.t("content.idea.when", Labels.slot(r.topSlots().get(0))));
        return String.join("\n", parts);
    }

    private void formatsBody(View v, Report r) {
        boolean any = section(v, false, Tr.t("content.formats"), r.kinds(), Labels::kind);
        any |= section(v, any, Tr.t("content.lengths"), r.lengths(), Labels::length);
        any |= section(v, any, Tr.t("content.links"), r.links(), Labels::link);
        any |= section(v, any, Tr.t("content.per_day"), r.frequency(), Labels::frequency);
        if (!any) v.line(Tr.t("content.no_variety"));
    }

    private void topicsBody(View v, Report r) {
        if (r.topics().isEmpty()) {
            v.line("<b>" + Tr.t("content.topics") + "</b>").line(Tr.t("content.no_topics"));
        } else {
            section(v, false, Tr.t("content.topics"), r.topics().size() > 12 ? r.topics().subList(0, 12) : r.topics(), Labels::topic);
        }
        List<Group> kws = r.keywords();
        if (kws.isEmpty()) {
            v.blank().line(Tr.t("content.words"));
            if (r.eligible() < AnalysisEngine.MIN_POSTS_FOR_KEYWORDS)
                v.line(Tr.t("content.words_start", AnalysisEngine.MIN_POSTS_FOR_KEYWORDS, r.eligible()));
            else
                v.line(Tr.t("content.words_none", AnalysisEngine.minKeywordPosts(r.eligible())));
        } else {
            List<Group> win = kws.stream().filter(g -> g.lift() > 1).limit(8).toList();
            List<Group> lose = kws.stream().filter(g -> g.lift() < 1).sorted(Comparator.comparingDouble(Group::lift)).limit(5).toList();
            v.blank().line(Tr.t("content.words_win"));
            for (Group g : win) v.line("▲ “" + Fmt.esc(g.key()) + "” – " + Fmt.lift(g.lift()) + " · " + g.posts());
            if (!lose.isEmpty()) {
                v.blank().line(Tr.t("content.words_lose"));
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
        for (Group g : groups) v.line("• " + label.apply(g.key()) + " – " + Fmt.lift(g.lift()) + " · " + Tr.n("n.posts", g.posts()));
        return true;
    }

    // ------------------------------------------------------------------ top posts

    public View top(Channel ch, Role role, int page) {
        Report r = analysis.report(ch);
        List<Scored> all = r.topPosts().size() > MAX_TOP ? r.topPosts().subList(0, MAX_TOP) : r.topPosts();
        View v = new View().line(Tr.t("top.title")).line("<i>" + Fmt.esc(ch.getTitle()) + " · " + Labels.window(ch.getWindowDays()) + "</i>").blank();
        long id = ch.getId();
        if (all.isEmpty()) {
            v.line(Tr.t("top.empty"));
            return v.row(Btn.cb(Tr.t("btn.dashboard"), Cb.of("nav:home", id)));
        }
        int pages = (all.size() + PAGE - 1) / PAGE;
        page = Math.max(0, Math.min(page, pages - 1));
        boolean moderate = role.can(Permission.MODERATE_POSTS);
        for (int i = page * PAGE; i < Math.min(all.size(), (page + 1) * PAGE); i++) {
            Sample s = all.get(i).sample();
            String title = s.text().isBlank() ? Labels.kind(s.kind().name()) : Labels.kindEmoji(s.kind().name()) + " " + Fmt.trunc(s.text(), 70);
            String comments = s.commentsKnown() && s.comments() > 0 ? " · 💭 " + s.comments() : "";
            v.line("<b>" + (i + 1) + ".</b> " + Fmt.esc(title));
            v.line("     ❤ " + s.reactions() + comments + " · " + when(s.postedAt().atZone(ch.zone())));
            List<Btn> row = new ArrayList<>();
            String url = Links.post(ch.getTelegramChatId(), ch.getUsername(), ch.isDemo(), s.messageId());
            if (url != null) row.add(Btn.link("↗ " + (i + 1), url));
            if (moderate) {
                row.add(Btn.cb("🏷 " + (i + 1), Cb.of("pm:topic", id, s.id())));
                row.add(Btn.cb("🚫 " + (i + 1), Cb.of("pm:excl", id, s.id(), page)));
            }
            v.row(row.toArray(new Btn[0]));
        }
        if (moderate) v.blank().line(Tr.t("top.moderate_hint"));
        List<Btn> nav = new ArrayList<>();
        if (page > 0) nav.add(Btn.cb(Tr.t("btn.prev"), Cb.of("nav:top", id, page - 1)));
        nav.add(Btn.cb((page + 1) + " / " + pages, Cb.of("nav:top", id, page)));
        if (page < pages - 1) nav.add(Btn.cb(Tr.t("btn.next"), Cb.of("nav:top", id, page + 1)));
        v.row(nav.toArray(new Btn[0]));
        if (moderate) v.row(Btn.cb(Tr.t("top.btn.excluded"), Cb.of("nav:excluded", id)));
        v.row(Btn.cb(Tr.t("btn.dashboard"), Cb.of("nav:home", id)));
        return v;
    }

    public View excluded(Channel ch, Role role) {
        List<Sample> ex = analysis.samples(ch).stream().filter(Sample::excluded)
                .sorted(Comparator.comparing(Sample::postedAt).reversed()).limit(10).toList();
        View v = new View().line(Tr.t("excluded.title")).line(Tr.t("excluded.hint")).blank();
        long id = ch.getId();
        if (ex.isEmpty()) v.line(Tr.t("excluded.none"));
        for (Sample s : ex) {
            v.line("• " + Fmt.esc(Fmt.trunc(s.text().isBlank() ? Labels.kind(s.kind().name()) : s.text(), 60)) + " · ❤ " + s.reactions());
            v.rowIf(role.can(Permission.MODERATE_POSTS), Btn.cb(Tr.t("excluded.restore", Fmt.trunc(s.text().isBlank() ? Labels.kindName(s.kind().name()) : s.text(), 18)), Cb.of("pm:excl", id, s.id(), "x")));
        }
        return v.row(Btn.cb(Tr.t("top.btn.back"), Cb.of("nav:top", id, 0)));
    }

    // ------------------------------------------------------------------ growth

    public View growth(Channel ch, int days) {
        SubscriberService.Growth g = subscribers.growth(ch, days);
        View v = new View().line(Tr.t("growth.title")).line("<i>" + Fmt.esc(ch.getTitle()) + " · " + Labels.window(days) + "</i>").blank();
        if (g.current() != null) v.line(Tr.t("growth.now", Fmt.num(g.current())));
        if (g.series().size() < 2 || g.change() == null) {
            v.line(Tr.t("growth.need_days"));
        } else {
            double perDay = g.change() / (double) days;
            v.line((g.change() >= 0 ? "▲ " : "▼ ") + Tr.t("growth.change", Fmt.signed(g.change()), Labels.window(days), (perDay >= 0 ? "+" : "") + Fmt.one(perDay)));
            v.line("<pre>" + Fmt.sparkline(downsample(g.series(), 40)) + "</pre>");
            v.line("<i>" + Fmt.num(g.series().get(0)) + " → " + Fmt.num(g.series().get(g.series().size() - 1)) + "</i>");
        }
        long id = ch.getId();
        List<Btn> chips = new ArrayList<>();
        for (int d : new int[]{30, 90, 180}) chips.add(Btn.cb((d == days ? "✓ " : "") + Labels.windowShort(d), Cb.of("nav:growth", id, d)));
        v.row(chips.toArray(new Btn[0]));
        return v.row(Btn.cb(Tr.t("btn.dashboard"), Cb.of("nav:home", id)));
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
            v.line(Tr.t("gate.no_signal"))
                    .line(Tr.t(ch.getDiscussionChatId() == null ? "gate.enable_reactions_and_comments" : "gate.enable_reactions"));
        } else if (r.eligible() < AnalysisEngine.MIN_POSTS_FOR_TIMES) {
            v.line(Tr.t("gate.not_enough"))
                    .line(Fmt.progress(r.eligible(), AnalysisEngine.MIN_POSTS_FOR_TIMES, 10) + "  " + r.eligible() + " / " + AnalysisEngine.MIN_POSTS_FOR_TIMES)
                    .line(Tr.t("gate.day_old") + (ch.isHistoryImported() || ch.isDemo() ? "" : " " + Tr.t("gate.import")));
            if (r.totalInWindow() > r.eligible()) v.line(Tr.t("gate.more_excluded", r.totalInWindow() - r.eligible()));
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
        return v.row(Btn.cb(Tr.t("btn.dashboard"), Cb.of("nav:home", id)));
    }

    /** "12 Oct 18:40" in the current language. */
    static String when(java.time.ZonedDateTime t) {
        return DateTimeFormatter.ofPattern("d MMM HH:mm", Tr.locale()).format(t).replace(".", "");
    }

    /** Day names padded to three characters so the bars line up in any language. */
    static String pad3(String day) {
        int n = day.codePointCount(0, day.length());
        if (n >= 3) return day.substring(0, day.offsetByCodePoints(0, 3));
        return day + " ".repeat(3 - n);
    }
}
