package com.halty.gramalyzerbot.bot.screen;

import com.halty.gramalyzerbot.bot.BotApi;
import com.halty.gramalyzerbot.i18n.Lang;
import com.halty.gramalyzerbot.i18n.Tr;
import com.halty.gramalyzerbot.bot.ui.Btn;
import com.halty.gramalyzerbot.bot.ui.Cb;
import com.halty.gramalyzerbot.bot.ui.Fmt;
import com.halty.gramalyzerbot.bot.ui.View;
import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** First-run experience, connecting a channel, channel switching, help and the language picker. */
@Component
@RequiredArgsConstructor
public class StartScreens {

    private final BotApi gateway;

    public String welcomeCaption(User u) {
        return Tr.t("start.caption", Fmt.esc(u.displayName()));
    }

    public View welcome() {
        return View.of(Tr.t("start.where"))
                .row(Btn.cb(Tr.t("start.btn.connect"), "ch:connect"))
                .row(Btn.cb(Tr.t("start.btn.track"), "wl:list"))
                .row(Btn.cb(Tr.t("start.btn.demo"), "ch:demo"), Btn.cb(Tr.t("start.btn.how"), "help:home"))
                .row(Btn.cb(Tr.t("btn.language"), "lang:home"));
    }

    public View connect() {
        return View.of(Tr.t("start.connect.title"))
                .blank()
                .line(Tr.t("start.connect.step1"))
                .line(Tr.t("start.connect.step2"))
                .line(Tr.t("start.connect.step3"))
                .blank()
                .line(Tr.t("start.connect.by_hand", Fmt.esc(gateway.botUsername())))
                .blank()
                .line(Tr.t("start.connect.already"))
                .row(Btn.link(Tr.t("btn.add_to_channel"), "https://t.me/" + gateway.botUsername() + "?startchannel=true"))
                .row(Btn.cb(Tr.t("start.btn.added"), "ch:check"))
                .row(Btn.cb(Tr.t("btn.back"), "ch:list"));
    }

    public View connected(Channel ch) {
        return View.of(Tr.t("start.connected.title", Fmt.esc(ch.getTitle())))
                .blank()
                .line(Tr.t("start.connected.recording"))
                .line(Tr.t("start.connected.setup"))
                .row(Btn.cb(Tr.t("start.btn.timezone"), Cb.of("set:tz", ch.getId())), Btn.cb(Tr.t("start.btn.import"), Cb.of("set:import", ch.getId())))
                .row(Btn.cb(Tr.t("btn.open_dashboard"), Cb.of("nav:home", ch.getId())));
    }

    public View channels(List<Channel> mine, Long activeId, boolean botAdmin) {
        View v = View.of(Tr.t("start.channels.title"));
        if (mine.isEmpty()) v.blank().line(Tr.t("start.channels.none"));
        for (Channel c : mine) {
            String mark = c.getId().equals(activeId) ? "✅ " : "";
            String icon = c.isDemo() ? "🎮 " : c.isActive() ? "📺 " : "⚠️ ";
            v.row(Btn.cb(mark + icon + Fmt.trunc(c.getTitle(), 32), Cb.of("ch:pick", c.getId())));
        }
        v.row(Btn.cb(Tr.t("start.btn.connect_another"), "ch:connect"));
        v.row(Btn.cb(Tr.t("btn.watchlist"), "wl:list"));
        v.rowIf(mine.stream().noneMatch(Channel::isDemo), Btn.cb(Tr.t("start.btn.explore"), "ch:demo"));
        v.rowIf(botAdmin, Btn.cb(Tr.t("start.btn.admin"), "adm:home"));
        return v;
    }

    public View help(boolean hasChannel) {
        View v = View.of(Tr.t("help.title"))
                .blank()
                .line(Tr.t("help.collect.title"))
                .line(Tr.t("help.collect"))
                .blank()
                .line(Tr.t("help.analyse.title"))
                .line(Tr.t("help.analyse"))
                .blank()
                .line(Tr.t("help.advise.title"))
                .line(Tr.t("help.advise"))
                .blank()
                .line(Tr.t("help.watch.title"))
                .line(Tr.t("help.watch"))
                .blank()
                .line(Tr.t("help.cant_see.title"))
                .line(Tr.t("help.cant_see"))
                .blank()
                .line(Tr.t("help.team.title"))
                .line(Tr.t("help.team"))
                .blank()
                .line(Tr.t("help.tip"));
        return v.row(Btn.cb(hasChannel ? Tr.t("help.back_dashboard") : Tr.t("btn.back"), hasChannel ? "nav:active" : "ch:list"));
    }

    /** The language picker; the current language is ticked. */
    public View languages(Lang current) {
        View v = View.of(Tr.t("lang.title")).blank().line(Tr.t("lang.hint"));
        List<Btn> row = new ArrayList<>();
        for (Lang l : Lang.values()) {
            row.add(Btn.cb((l == current ? "✅ " : "") + l.label(), Cb.of("lang:set", l.code())));
            if (row.size() == 2) {
                v.row(row.toArray(new Btn[0]));
                row.clear();
            }
        }
        if (!row.isEmpty()) v.row(row.toArray(new Btn[0]));
        return v.row(Btn.cb(Tr.t("lang.auto"), "lang:set:auto"), Btn.cb(Tr.t("btn.back"), "nav:active"));
    }
}
