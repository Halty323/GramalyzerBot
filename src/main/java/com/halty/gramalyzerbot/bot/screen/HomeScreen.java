package com.halty.gramalyzerbot.bot.screen;

import com.halty.gramalyzerbot.analytics.AnalysisEngine;
import com.halty.gramalyzerbot.analytics.Report;
import com.halty.gramalyzerbot.i18n.Tr;
import com.halty.gramalyzerbot.bot.ui.*;
import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.channel.Permission;
import com.halty.gramalyzerbot.channel.Role;
import com.halty.gramalyzerbot.channel.SubscriberService;
import com.halty.gramalyzerbot.channel.TimeZones;
import com.halty.gramalyzerbot.post.AnalysisService;
import com.halty.gramalyzerbot.post.Post;
import com.halty.gramalyzerbot.post.PostService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The dashboard: numbers, the playbook, setup progress and navigation. */
@Component
@RequiredArgsConstructor
public class HomeScreen {

    private final AnalysisService analysis;
    private final SubscriberService subscribers;
    private final PostService posts;

    public View render(Channel ch, Role role) {
        Instant now = Instant.now();
        Report r = analysis.report(ch);
        SubscriberService.Growth g = subscribers.growth(ch, 30);
        Optional<Post> last = posts.latest(ch);
        long total = posts.count(ch);

        View v = new View();
        v.line("📺 <b>" + Fmt.esc(ch.getTitle()) + "</b>" + (ch.getUsername() != null && !ch.isDemo() ? "  <i>@" + Fmt.esc(ch.getUsername()) + "</i>" : ""));
        v.line(Fmt.rule());
        if (g.current() != null) {
            String delta = g.change() == null ? "" : "   " + (g.change() >= 0 ? "▲ " : "▼ ") + Fmt.signed(g.change()) + " (" + Labels.windowShort(30) + ")";
            v.line(Tr.t("home.subscribers", Fmt.num(g.current())) + delta);
        }
        v.line(Tr.t("home.posts", Fmt.num(total), Fmt.num(r.eligible()), Labels.window(ch.getWindowDays())));
        if (r.eligible() > 0) {
            String comments = r.avgComments() > 0 ? " · 💭 " + Fmt.one(r.avgComments()) : "";
            v.line(Tr.t("home.average", Fmt.one(r.avgReactions())) + comments);
        }
        v.line(Tr.t("home.last_post", last.map(p -> Fmt.ago(p.getPostedAt(), now)).orElse(Tr.t("home.none_yet"))));
        v.blank();

        List<String> play = Playbook.lines(r);
        if (!play.isEmpty()) {
            v.line(Tr.t("home.playbook"));
            v.line("<blockquote>" + String.join("\n", play) + "</blockquote>");
            v.line("<i>" + Labels.confidence(r.confidence()) + " · " + Tr.n("home.based_on", r.eligible()) + "</i>");
            if (r.notes().contains(Report.Note.LOW_TIME_VARIETY))
                v.line(Tr.t("home.low_variety"));
        } else if (r.eligible() > 0 && !r.hasSignal()) {
            v.line(Tr.t("home.no_signal"));
            v.line(Tr.t("home.no_signal_hint"));
        } else {
            int have = r.eligible(), need = AnalysisEngine.MIN_POSTS_FOR_TIMES;
            v.line(Tr.t("home.learning"));
            v.line(Fmt.progress(have, need, 10) + "  " + Tr.t("home.progress", have, need));
            if (!ch.isHistoryImported() && !ch.isDemo()) v.line(Tr.t("home.skip_wait"));
            else v.line(Tr.t("home.day_old"));
        }

        if (role.can(Permission.CHANGE_SETTINGS) && !ch.isDemo() && (!ch.isTimezoneConfirmed() || !ch.isHistoryImported())) {
            v.blank();
            v.line(Tr.t("home.setup"));
            v.line(Tr.t("home.setup.connected"));
            v.line((ch.isTimezoneConfirmed() ? "✅" : "⬜") + " " + Tr.t("home.setup.timezone", Fmt.esc(TimeZones.label(ch.zone(), now))));
            v.line((ch.isHistoryImported() ? "✅" : "⬜") + " " + Tr.t("home.setup.history"));
            v.line((ch.getDiscussionChatId() != null ? "✅" : "⬜") + " " + Tr.t("home.setup.comments"));
        }

        String health = health(ch, now);
        if (health != null) {
            v.blank();
            v.line(health);
        }

        long id = ch.getId();
        v.row(Btn.cb(Tr.t("btn.times"), Cb.of("nav:times", id, ch.getWindowDays(), "main")),
                Btn.cb(Tr.t("btn.content"), Cb.of("nav:content", id, ch.getWindowDays(), "main")));
        v.row(Btn.cb(Tr.t("btn.top"), Cb.of("nav:top", id, 0)), Btn.cb(Tr.t("btn.growth"), Cb.of("nav:growth", id, 30)));
        if (role.can(Permission.CHANGE_SETTINGS)) {
            v.row(Btn.cb(Tr.t("btn.settings"), Cb.of("set:home", id)), Btn.cb(Tr.t("btn.team"), Cb.of("team:home", id)));
        } else {
            v.row(Btn.cb(Tr.t("btn.my_notifications"), Cb.of("me:home", id)), Btn.cb(Tr.t("btn.team"), Cb.of("team:home", id)));
        }
        v.row(Btn.cb(Tr.t("btn.watchlist"), "wl:list"), Btn.cb(Tr.t("btn.channels"), "ch:list"));
        v.row(Btn.cb(Tr.t("btn.refresh"), Cb.of("nav:home", id)), Btn.cb(Tr.t("btn.language"), "lang:home"), Btn.cb(Tr.t("btn.help"), "help:home"));
        return v;
    }

    /** One line about whether data is flowing, or null when there's nothing to say. */
    static String health(Channel ch, Instant now) {
        if (ch.isDemo()) return Tr.t("home.health.demo");
        if (!ch.isActive()) return Tr.t("home.health.removed");
        if (ch.getLastPostEventAt() == null) return Tr.t("home.health.waiting");
        if (ch.getLastReactionEventAt() == null && ch.getLastPostEventAt().isBefore(now.minusSeconds(6 * 3600)))
            return Tr.t("home.health.no_reactions");
        return Tr.t("home.health.live", Fmt.ago(ch.getLastPostEventAt(), now));
    }
}
