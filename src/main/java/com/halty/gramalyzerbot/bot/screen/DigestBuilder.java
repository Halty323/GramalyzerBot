package com.halty.gramalyzerbot.bot.screen;

import com.halty.gramalyzerbot.analytics.Report;
import com.halty.gramalyzerbot.analytics.Sample;
import com.halty.gramalyzerbot.i18n.Tr;
import com.halty.gramalyzerbot.bot.ui.*;
import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.channel.Links;
import com.halty.gramalyzerbot.channel.SubscriberService;
import com.halty.gramalyzerbot.post.AnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** The weekly digest message sent to team members who opted in. */
@Component
@RequiredArgsConstructor
public class DigestBuilder {

    private final AnalysisService analysis;
    private final SubscriberService subscribers;

    record Week(int posts, int reactions, int comments) {}

    public View build(Channel ch) {
        Instant now = Instant.now();
        List<Sample> all = analysis.samples(ch).stream().filter(s -> !s.excluded()).toList();
        Instant weekAgo = now.minus(Duration.ofDays(7)), twoWeeksAgo = now.minus(Duration.ofDays(14));
        List<Sample> thisWeek = all.stream().filter(s -> s.postedAt().isAfter(weekAgo) && !s.postedAt().isAfter(now)).toList();
        List<Sample> prevWeek = all.stream().filter(s -> s.postedAt().isAfter(twoWeeksAgo) && !s.postedAt().isAfter(weekAgo)).toList();
        Week cur = week(thisWeek), prev = week(prevWeek);

        View v = new View();
        v.line(Tr.t("digest.title", Fmt.esc(ch.getTitle())));
        v.line(Fmt.rule());
        if (cur.posts() == 0) {
            v.line(Tr.t("digest.no_posts"));
        } else {
            v.line(Tr.t("digest.posts", cur.posts()) + trend(cur.posts(), prev.posts()));
            v.line(Tr.t("digest.reactions", Fmt.num(cur.reactions())) + trend(cur.reactions(), prev.reactions()));
            if (cur.comments() > 0 || prev.comments() > 0)
                v.line(Tr.t("digest.comments", Fmt.num(cur.comments())) + trend(cur.comments(), prev.comments()));
            Optional<Sample> best = thisWeek.stream().max(Comparator.comparingInt(s -> s.reactions() + 3 * s.comments()));
            best.ifPresent(b -> {
                v.line(Tr.t("digest.best", Fmt.esc(Fmt.trunc(b.text().isEmpty() ? Labels.kind(b.kind().name()) : b.text(), 60))) + " · ❤ " + b.reactions());
                String url = Links.post(ch.getTelegramChatId(), ch.getUsername(), ch.isDemo(), b.messageId());
                if (url != null) v.row(Btn.link(Tr.t("digest.btn.best"), url));
            });
        }
        SubscriberService.Growth g = subscribers.growth(ch, 7);
        if (g.current() != null) v.line(Tr.t("digest.subscribers", Fmt.num(g.current())) + (g.change() == null ? "" : " (" + Tr.t("digest.this_week", Fmt.signed(g.change())) + ")"));

        List<String> play = Playbook.lines(analysis.report(ch));
        if (!play.isEmpty()) {
            v.blank();
            v.line(Tr.t("digest.aim"));
            v.line("<blockquote>" + String.join("\n", play) + "</blockquote>");
        }
        v.row(Btn.cb(Tr.t("btn.open_dashboard"), Cb.of("nav:home", ch.getId())));
        return v;
    }

    static Week week(List<Sample> list) {
        int r = 0, c = 0;
        for (Sample s : list) {
            r += s.reactions();
            if (s.commentsKnown()) c += s.comments();
        }
        return new Week(list.size(), r, c);
    }

    /** " (▲ 25%)" style suffix, empty when there's nothing to compare with. */
    static String trend(int cur, int prev) {
        if (prev <= 0) return "";
        int pct = (int) Math.round((cur - prev) * 100.0 / prev);
        if (pct == 0) return " (±0%)";
        return " (" + (pct > 0 ? "▲ " : "▼ ") + Math.abs(pct) + "%)";
    }
}
