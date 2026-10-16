package com.halty.gramalyzerbot.bot.screen;

import com.halty.gramalyzerbot.analytics.AnalysisEngine;
import com.halty.gramalyzerbot.analytics.Report;
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
            String delta = g.change() == null ? "" : "   " + (g.change() >= 0 ? "▲ " : "▼ ") + Fmt.signed(g.change()) + " (30d)";
            v.line("👥 <b>" + Fmt.num(g.current()) + "</b> subscribers" + delta);
        }
        v.line("📝 <b>" + Fmt.num(total) + "</b> posts recorded · <b>" + Fmt.num(r.eligible()) + "</b> analysed (" + Labels.window(ch.getWindowDays()) + ")");
        if (r.eligible() > 0) {
            String comments = r.avgComments() > 0 ? " · 💭 " + Fmt.one(r.avgComments()) : "";
            v.line("📈 Average post: ❤️ " + Fmt.one(r.avgReactions()) + comments);
        }
        v.line("🕒 Last post: " + last.map(p -> Fmt.ago(p.getPostedAt(), now)).orElse("none yet"));
        v.blank();

        List<String> play = Playbook.lines(r);
        if (!play.isEmpty()) {
            v.line("<b>🎯 Your playbook</b>");
            v.line("<blockquote>" + String.join("\n", play) + "</blockquote>");
            v.line("<i>" + Labels.confidence(r.confidence()) + " · based on " + r.eligible() + " posts</i>");
            if (r.notes().contains(Report.Note.LOW_TIME_VARIETY))
                v.line("💡 <i>You post at almost the same time every day, so I can't compare hours yet – try the experiments under Best times.</i>");
        } else if (r.eligible() > 0 && !r.hasSignal()) {
            v.line("🤔 <b>No reactions or comments yet.</b>");
            v.line("Check that reactions are switched on for your channel – without them I have nothing to learn from.");
        } else {
            int have = r.eligible(), need = AnalysisEngine.MIN_POSTS_FOR_TIMES;
            v.line("🌱 <b>Still learning your channel</b>");
            v.line(Fmt.progress(have, need, 10) + "  " + have + " / " + need + " posts");
            if (!ch.isHistoryImported() && !ch.isDemo()) v.line("<i>Skip the wait: import your history with /import.</i>");
            else v.line("<i>Posts count once they're a day old, so their reactions have settled.</i>");
        }

        if (role.can(Permission.CHANGE_SETTINGS) && !ch.isDemo() && (!ch.isTimezoneConfirmed() || !ch.isHistoryImported())) {
            v.blank();
            v.line("<b>🧰 Setup</b>");
            v.line("✅ Channel connected");
            v.line((ch.isTimezoneConfirmed() ? "✅" : "⬜") + " Timezone: " + Fmt.esc(TimeZones.label(ch.zone(), now)));
            v.line((ch.isHistoryImported() ? "✅" : "⬜") + " History imported <i>(optional)</i>");
            v.line((ch.getDiscussionChatId() != null ? "✅" : "⬜") + " Comments tracking <i>(optional)</i>");
        }

        String health = health(ch, now);
        if (health != null) {
            v.blank();
            v.line(health);
        }

        long id = ch.getId();
        v.row(Btn.cb("⏰ Best times", Cb.of("nav:times", id, ch.getWindowDays(), "main")),
                Btn.cb("💡 What to write", Cb.of("nav:content", id, ch.getWindowDays(), "main")));
        v.row(Btn.cb("🏆 Top posts", Cb.of("nav:top", id, 0)), Btn.cb("📈 Growth", Cb.of("nav:growth", id, 30)));
        if (role.can(Permission.CHANGE_SETTINGS)) {
            v.row(Btn.cb("⚙️ Settings", Cb.of("set:home", id)), Btn.cb("👥 Team", Cb.of("team:home", id)));
        } else {
            v.row(Btn.cb("🔔 My notifications", Cb.of("me:home", id)), Btn.cb("👥 Team", Cb.of("team:home", id)));
        }
        v.row(Btn.cb("🔀 Channels", "ch:list"), Btn.cb("🔄 Refresh", Cb.of("nav:home", id)), Btn.cb("❓ Help", "help:home"));
        return v;
    }

    /** One line about whether data is flowing, or null when there's nothing to say. */
    static String health(Channel ch, Instant now) {
        if (ch.isDemo()) return "🎮 <i>This is sample data – connect your own channel any time from 🔀 Channels.</i>";
        if (!ch.isActive()) return "🔴 <b>I'm no longer an admin of this channel.</b> Add me again to keep collecting posts.";
        if (ch.getLastPostEventAt() == null) return "🟡 Waiting for the next post – publish something and I'll pick it up.";
        if (ch.getLastReactionEventAt() == null && ch.getLastPostEventAt().isBefore(now.minusSeconds(6 * 3600)))
            return "🟡 No reaction updates yet. They can take a while to arrive; make sure reactions are enabled on the channel.";
        return "🟢 Live – last post seen " + Fmt.ago(ch.getLastPostEventAt(), now);
    }
}
