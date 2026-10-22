package com.halty.gramalyzerbot.bot.screen;

import com.halty.gramalyzerbot.billing.BillingService;
import com.halty.gramalyzerbot.i18n.Tr;
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
    private final BillingService billing;

    public View home() {
        boolean open = settings.publicRegistration();
        long[] month = billing.lastMonth();
        return new View().line(Tr.t("adm.title")).line(Fmt.rule())
                .line(Tr.t("adm.users_line", Fmt.num(users.count()), users.bannedCount()))
                .line(Tr.t("adm.channels_line", Fmt.num(channels.count())))
                .line(Tr.t("adm.who_line", Tr.t(open ? "adm.anyone" : "adm.invited")))
                .line(billing.enabled() ? Tr.t("adm.payments_line", month[0], Fmt.num(month[1])) : Tr.t("adm.payments_off"))
                .row(Btn.cb(Tr.t("adm.btn.users"), "adm:users:0"), Btn.cb(Tr.t("adm.btn.channels"), "adm:chs:0"))
                .row(Btn.cb(Tr.t(open ? "adm.btn.close" : "adm.btn.open"), "adm:reg"))
                .row(Btn.cb(Tr.t("adm.btn.ban"), "adm:banask"))
                .row(Btn.cb(Tr.t("btn.back"), "ch:list"));
    }

    public View users(int page) {
        List<User> list = users.page(page, PAGE);
        View v = new View().line(Tr.t("adm.users_title", page + 1));
        if (list.isEmpty()) v.blank().line(Tr.t("adm.no_users"));
        for (User u : list) {
            v.row(Btn.cb((u.isBanned() ? "🚫 " : u.isBotAdmin() ? "🛠 " : "👤 ") + Fmt.trunc(u.displayName(), 24)
                    + " · " + Fmt.ago(u.getLastSeenAt(), Instant.now()), "adm:user:" + u.getTelegramId()));
        }
        List<Btn> nav = new java.util.ArrayList<>();
        if (page > 0) nav.add(Btn.cb(Tr.t("btn.prev"), "adm:users:" + (page - 1)));
        if (list.size() == PAGE) nav.add(Btn.cb(Tr.t("btn.next"), "adm:users:" + (page + 1)));
        v.row(nav.toArray(new Btn[0]));
        return v.row(Btn.cb(Tr.t("adm.btn.back"), "adm:home"));
    }

    public View user(User u) {
        View v = new View().line("👤 <b>" + Fmt.esc(u.displayName()) + "</b>")
                .line("ID: <code>" + u.getTelegramId() + "</code>" + (u.getUsername() != null ? " · @" + Fmt.esc(u.getUsername()) : ""))
                .line(Tr.t("adm.user_seen", Fmt.ago(u.getCreatedAt(), Instant.now()), Fmt.ago(u.getLastSeenAt(), Instant.now())))
                .line(Tr.t(u.isBanned() ? "adm.status.banned" : u.isBotAdmin() ? "adm.status.admin" : "adm.status.active"))
                .line(Tr.t("adm.user_plan", PlanScreens.name(billing.plan(u))));
        if (!u.isBotAdmin()) {
            v.row(u.isBanned() ? Btn.cb(Tr.t("adm.btn.unban"), "adm:unban:" + u.getTelegramId()) : Btn.cb(Tr.t("adm.btn.do_ban"), "adm:ban:" + u.getTelegramId()));
        }
        return v.row(Btn.cb(Tr.t("adm.btn.users_back"), "adm:users:0"));
    }

    public View channels(int page) {
        List<Channel> list = channels.page(page, PAGE);
        View v = new View().line(Tr.t("adm.channels_title", page + 1));
        if (list.isEmpty()) v.blank().line(Tr.t("adm.no_channels"));
        for (Channel c : list)
            v.row(Btn.cb((c.isDemo() ? "🎮 " : c.isActive() ? "📺 " : "⚠️ ") + Fmt.trunc(c.getTitle(), 30), "nav:home:" + c.getId()));
        List<Btn> nav = new java.util.ArrayList<>();
        if (page > 0) nav.add(Btn.cb(Tr.t("btn.prev"), "adm:chs:" + (page - 1)));
        if (list.size() == PAGE) nav.add(Btn.cb(Tr.t("btn.next"), "adm:chs:" + (page + 1)));
        v.row(nav.toArray(new Btn[0]));
        return v.row(Btn.cb(Tr.t("adm.btn.back"), "adm:home"));
    }

    public View banPrompt() {
        return new View().line(Tr.t("adm.ban_title")).blank()
                .line(Tr.t("adm.ban_hint"))
                .row(Btn.cb(Tr.t("btn.cancel"), "adm:home"));
    }
}
