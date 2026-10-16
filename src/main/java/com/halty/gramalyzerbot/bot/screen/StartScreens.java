package com.halty.gramalyzerbot.bot.screen;

import com.halty.gramalyzerbot.bot.BotApi;
import com.halty.gramalyzerbot.bot.ui.Btn;
import com.halty.gramalyzerbot.bot.ui.Cb;
import com.halty.gramalyzerbot.bot.ui.Fmt;
import com.halty.gramalyzerbot.bot.ui.View;
import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** First-run experience, connecting a channel, channel switching and help. */
@Component
@RequiredArgsConstructor
public class StartScreens {

    private final BotApi gateway;

    public String welcomeCaption(User u) {
        return "👋 <b>Hi " + Fmt.esc(u.displayName()) + "!</b> I'm <b>Gramalyzer</b>, your channel's analyst.\n\n"
                + "I study how your posts perform and tell you\n"
                + "⏰ <b>when</b> to publish\n"
                + "💡 <b>what</b> to write about\n\n"
                + "Everything is set up right here in chat. Ready?";
    }

    public View welcome() {
        return View.of("Where would you like to start?")
                .row(Btn.cb("➕ Connect my channel", "ch:connect"))
                .row(Btn.cb("🎮 Try it with sample data", "ch:demo"), Btn.cb("❓ How it works", "help:home"));
    }

    public View connect() {
        return View.of("➕ <b>Connect your channel</b>")
                .blank()
                .line("1️⃣ Tap the button below and pick your channel")
                .line("2️⃣ Confirm – no special rights needed, I only read and <b>never post</b>")
                .line("3️⃣ I'll message you here the moment I'm in")
                .blank()
                .line("<i>By hand: channel → Administrators → Add Admin → @" + Fmt.esc(gateway.botUsername()) + "</i>")
                .blank()
                .line("Already added me? Tap <b>I've added it</b>, send the channel's <code>@username</code>, or forward me any post from it.")
                .row(Btn.link("➕ Add me to a channel", "https://t.me/" + gateway.botUsername() + "?startchannel=true"))
                .row(Btn.cb("✅ I've added it", "ch:check"))
                .row(Btn.cb("‹ Back", "ch:list"));
    }

    public View connected(Channel ch) {
        return View.of("🎉 <b>Connected to " + Fmt.esc(ch.getTitle()) + "!</b>")
                .blank()
                .line("From now on I record every new post and its reactions.")
                .line("<b>Quick setup</b> (30 seconds):")
                .row(Btn.cb("🌍 Set timezone", Cb.of("set:tz", ch.getId())), Btn.cb("📥 Import history", Cb.of("set:import", ch.getId())))
                .row(Btn.cb("📊 Open dashboard", Cb.of("nav:home", ch.getId())));
    }

    public View channels(List<Channel> mine, Long activeId, boolean botAdmin) {
        View v = View.of("🔀 <b>Your channels</b>");
        if (mine.isEmpty()) v.blank().line("You haven't connected a channel yet.");
        for (Channel c : mine) {
            String mark = c.getId().equals(activeId) ? "✅ " : "";
            String icon = c.isDemo() ? "🎮 " : c.isActive() ? "📺 " : "⚠️ ";
            v.row(Btn.cb(mark + icon + Fmt.trunc(c.getTitle(), 32), Cb.of("ch:pick", c.getId())));
        }
        v.row(Btn.cb("➕ Connect a channel", "ch:connect"));
        v.rowIf(mine.stream().noneMatch(Channel::isDemo), Btn.cb("🎮 Explore with sample data", "ch:demo"));
        v.rowIf(botAdmin, Btn.cb("🛠 Admin panel", "adm:home"));
        return v;
    }

    public View help(boolean hasChannel) {
        View v = View.of("❓ <b>How Gramalyzer works</b>")
                .blank()
                .line("<b>1 · Collect</b>")
                .line("Once I'm an admin of your channel I record every new post with its reactions – and comments, if you link the discussion group. Want history from day one? Import a Telegram export.")
                .blank()
                .line("<b>2 · Analyse</b>")
                .line("I compare posts by weekday, hour, format, length, topics and keywords, and shrink small samples so one lucky post doesn't look like a trend.")
                .blank()
                .line("<b>3 · Advise</b>")
                .line("Best times, content ideas, a weekly digest and optional “now is a good time” nudges.")
                .blank()
                .line("<b>What I can't see</b>")
                .line("Telegram doesn't give bots view counts, so “performance” means <b>reactions + comments</b>. Enable reactions on your channel for the best results.")
                .blank()
                .line("<b>Team</b>")
                .line("👑 Owner – everything · 🛡 Admin – settings &amp; moderators · 🔎 Moderator – insights, tag or exclude posts.")
                .blank()
                .line("<i>Tip: the buttons under the chat box jump straight to the main screens.</i>");
        return v.row(Btn.cb(hasChannel ? "‹ Back to dashboard" : "‹ Back", hasChannel ? "nav:active" : "ch:list"));
    }
}
