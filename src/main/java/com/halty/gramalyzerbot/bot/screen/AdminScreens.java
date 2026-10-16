package com.halty.gramalyzerbot.bot.screen;

import com.halty.gramalyzerbot.bot.ui.*;
import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.channel.ChannelService;
import com.halty.gramalyzerbot.settings.BotSettings;
import com.halty.gramalyzerbot.user.User;
import com.halty.gramalyzerbot.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/** The bot-wide admin panel (only for bot administrators). */
@Component
@RequiredArgsConstructor
public class AdminScreens {

    private static final int PAGE = 8;

    private final UserService users;
    private final ChannelService channels;
    private final BotSettings settings;

    public View home() {
        boolean open = settings.publicRegistration();
        return new View().line("🛠 <b>Admin panel</b>").line(Fmt.rule())
                .line("👥 Users: <b>" + Fmt.num(users.count()) + "</b> · 🚫 banned: <b>" + users.bannedCount() + "</b>")
                .line("📺 Connected channels: <b>" + Fmt.num(channels.count()) + "</b>")
                .line("🔓 Who may connect channels: <b>" + (open ? "anyone" : "invited people only") + "</b>")
                .row(Btn.cb("👥 Users", "adm:users:0"), Btn.cb("📺 Channels", "adm:chs:0"))
                .row(Btn.cb(open ? "🔒 Close registration" : "🔓 Open registration", "adm:reg"))
                .row(Btn.cb("🚫 Ban by Telegram ID", "adm:banask"))
                .row(Btn.cb("‹ Back", "ch:list"));
    }

    public View users(int page) {
        List<User> list = users.page(page, PAGE);
        View v = new View().line("👥 <b>Users</b> · page " + (page + 1));
        if (list.isEmpty()) v.blank().line("No more users.");
        for (User u : list) {
            v.row(Btn.cb((u.isBanned() ? "🚫 " : u.isBotAdmin() ? "🛠 " : "👤 ") + Fmt.trunc(u.displayName(), 24)
                    + " · " + Fmt.ago(u.getLastSeenAt(), Instant.now()), "adm:user:" + u.getTelegramId()));
        }
        List<Btn> nav = new java.util.ArrayList<>();
        if (page > 0) nav.add(Btn.cb("‹ Prev", "adm:users:" + (page - 1)));
        if (list.size() == PAGE) nav.add(Btn.cb("Next ›", "adm:users:" + (page + 1)));
        v.row(nav.toArray(new Btn[0]));
        return v.row(Btn.cb("‹ Admin panel", "adm:home"));
    }

    public View user(User u) {
        View v = new View().line("👤 <b>" + Fmt.esc(u.displayName()) + "</b>")
                .line("ID: <code>" + u.getTelegramId() + "</code>" + (u.getUsername() != null ? " · @" + Fmt.esc(u.getUsername()) : ""))
                .line("Joined " + Fmt.ago(u.getCreatedAt(), Instant.now()) + " · last seen " + Fmt.ago(u.getLastSeenAt(), Instant.now()))
                .line(u.isBanned() ? "Status: 🚫 banned" : u.isBotAdmin() ? "Status: 🛠 bot admin" : "Status: ✅ active");
        if (!u.isBotAdmin()) {
            v.row(u.isBanned() ? Btn.cb("✅ Unban", "adm:unban:" + u.getTelegramId()) : Btn.cb("🚫 Ban", "adm:ban:" + u.getTelegramId()));
        }
        return v.row(Btn.cb("‹ Users", "adm:users:0"));
    }

    public View channels(int page) {
        List<Channel> list = channels.page(page, PAGE);
        View v = new View().line("📺 <b>Channels</b> · page " + (page + 1));
        if (list.isEmpty()) v.blank().line("No more channels.");
        for (Channel c : list)
            v.row(Btn.cb((c.isDemo() ? "🎮 " : c.isActive() ? "📺 " : "⚠️ ") + Fmt.trunc(c.getTitle(), 30), "nav:home:" + c.getId()));
        List<Btn> nav = new java.util.ArrayList<>();
        if (page > 0) nav.add(Btn.cb("‹ Prev", "adm:chs:" + (page - 1)));
        if (list.size() == PAGE) nav.add(Btn.cb("Next ›", "adm:chs:" + (page + 1)));
        v.row(nav.toArray(new Btn[0]));
        return v.row(Btn.cb("‹ Admin panel", "adm:home"));
    }

    public View banPrompt() {
        return new View().line("🚫 <b>Ban a user</b>").blank()
                .line("Send me the person's numeric Telegram ID. They'll be locked out of the bot (their channels stay as they are).")
                .row(Btn.cb("✖ Cancel", "adm:home"));
    }
}
