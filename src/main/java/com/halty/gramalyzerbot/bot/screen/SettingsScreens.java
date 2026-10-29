package com.halty.gramalyzerbot.bot.screen;

import com.halty.gramalyzerbot.analytics.Metric;
import com.halty.gramalyzerbot.bot.BotApi;
import com.halty.gramalyzerbot.i18n.Tr;
import com.halty.gramalyzerbot.bot.ui.*;
import com.halty.gramalyzerbot.channel.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Channel configuration, all driven by buttons (and one typed prompt for the timezone). */
@Component
@RequiredArgsConstructor
public class SettingsScreens {

    private final BotApi gateway;

    public View home(Channel ch, Role role) {
        long id = ch.getId();
        View v = new View().line(Tr.t("set.title", Fmt.esc(ch.getTitle()))).line(Fmt.rule());
        v.line(Tr.t("set.timezone", Fmt.esc(TimeZones.label(ch.zone(), Instant.now()))) + (ch.isTimezoneConfirmed() ? "" : "  " + Tr.t("set.not_set")));
        v.line(Tr.t("set.window", Labels.window(ch.getWindowDays())));
        v.line(Tr.t("set.metric", Labels.metric(ch.getMetric()), Labels.formula(ch.getMetric())));
        v.line(Tr.t("set.digest", Fmt.dayShort(ch.getDigestDay()) + " " + Fmt.hour(ch.getDigestHour())));
        v.line(Tr.t("set.nudges", ch.getNudgeLeadMinutes(), ch.getNudgeGapHours()));
        v.line(Tr.t(ch.getDiscussionChatId() != null ? "set.comments.on" : "set.comments.off"));
        v.line(Tr.t("set.language", Tr.lang().label()));
        v.row(Btn.cb(Tr.t("set.btn.timezone"), Cb.of("set:tz", id)), Btn.cb(Tr.t("set.btn.window"), Cb.of("set:win", id)));
        v.row(Btn.cb(Tr.t("set.btn.metric"), Cb.of("set:met", id)), Btn.cb(Tr.t("set.btn.digest"), Cb.of("set:dig", id)));
        v.row(Btn.cb(Tr.t("set.btn.nudges"), Cb.of("set:nud", id)), Btn.cb(Tr.t("set.btn.comments"), Cb.of("set:com", id)));
        v.rowIf(role.can(Permission.IMPORT_DATA) && !ch.isDemo(), Btn.cb(Tr.t("start.btn.import"), Cb.of("set:import", id)));
        v.row(Btn.cb(Tr.t("btn.my_notifications"), Cb.of("me:home", id)), Btn.cb(Tr.t("btn.language"), "lang:home"));
        if (role.can(Permission.RESET_DATA)) {
            v.row(Btn.cb(Tr.t("set.btn.reset"), Cb.of("set:reset", id)),
                    Btn.cb(Tr.t(ch.isDemo() ? "set.btn.delete_demo" : "set.btn.disconnect"), Cb.of("set:disc", id)));
        }
        return v.row(Btn.cb(Tr.t("btn.dashboard"), Cb.of("nav:home", id)));
    }

    public View timezonePrompt(Channel ch) {
        long id = ch.getId();
        View v = new View().line(Tr.t("tz.title"))
                .blank()
                .line(Tr.t("tz.now", Fmt.esc(TimeZones.label(ch.zone(), Instant.now()))))
                .blank()
                .line(Tr.t("tz.type"))
                .line(Tr.t("tz.pick"));
        List<Btn> row = new ArrayList<>();
        for (String zone : TimeZones.POPULAR) {
            row.add(Btn.cb(TimeZones.cityName(java.time.ZoneId.of(zone)), Cb.of("set:tzset", id, zone)));
            if (row.size() == 2) {
                v.row(row.toArray(new Btn[0]));
                row.clear();
            }
        }
        if (!row.isEmpty()) v.row(row.toArray(new Btn[0]));
        return v.row(Btn.cb(Tr.t("set.btn.back"), Cb.of("set:home", id)));
    }

    public View timezoneChoices(Channel ch, List<java.time.ZoneId> zones) {
        View v = new View().line(Tr.t("tz.matches"));
        for (java.time.ZoneId z : zones)
            v.row(Btn.cb(TimeZones.label(z, Instant.now()), Cb.of("set:tzset", ch.getId(), z.getId())));
        return v.row(Btn.cb(Tr.t("btn.back"), Cb.of("set:tz", ch.getId())));
    }

    public View window(Channel ch) {
        long id = ch.getId();
        View v = new View().line(Tr.t("win.title")).blank()
                .line(Tr.t("win.hint"));
        List<Btn> chips = new ArrayList<>();
        for (int d : new int[]{30, 90, 180, 0})
            chips.add(Btn.cb((ch.getWindowDays() == d ? "✅ " : "") + (d == 0 ? Tr.t("win.all") : Tr.n("win.days", d)), Cb.of("set:winset", id, d)));
        v.row(chips.subList(0, 2).toArray(new Btn[0]));
        v.row(chips.subList(2, 4).toArray(new Btn[0]));
        return v.row(Btn.cb(Tr.t("set.btn.back"), Cb.of("set:home", id)));
    }

    public View metric(Channel ch) {
        long id = ch.getId();
        View v = new View().line(Tr.t("met.title")).blank()
                .line(Tr.t("met.hint"));
        List<Metric> metrics = Arrays.stream(Metric.values()).filter(m -> m != Metric.VIEWS || SettingsService.viewsKnown(ch)).toList();
        for (Metric m : metrics)
            v.line("• <b>" + Labels.metric(m) + "</b> – " + Labels.formula(m));
        if (!metrics.contains(Metric.VIEWS)) v.line("<i>" + Tr.t("met.views_private") + "</i>");
        for (Metric m : metrics)
            v.row(Btn.cb((ch.getMetric() == m ? "✅ " : "") + Labels.metric(m), Cb.of("set:metset", id, m.name())));
        return v.row(Btn.cb(Tr.t("set.btn.back"), Cb.of("set:home", id)));
    }

    public View digest(Channel ch) {
        long id = ch.getId();
        View v = new View().line(Tr.t("dig.title")).blank()
                .line(Tr.t("dig.hint", Fmt.esc(TimeZones.label(ch.zone(), Instant.now()))))
                .blank().line(Tr.t("dig.day"));
        List<Btn> days = new ArrayList<>();
        for (DayOfWeek d : DayOfWeek.values())
            days.add(Btn.cb((ch.getDigestDay() == d ? "✅ " : "") + Fmt.dayShort(d), Cb.of("set:dday", id, d.name())));
        v.row(days.subList(0, 4).toArray(new Btn[0]));
        v.row(days.subList(4, 7).toArray(new Btn[0]));
        v.blank().line(Tr.t("dig.hour"));
        List<Btn> row = new ArrayList<>();
        for (int h = 6; h <= 22; h++) {
            row.add(Btn.cb((ch.getDigestHour() == h ? "✅" : "") + String.format("%02d", h), Cb.of("set:dhour", id, h)));
            if (row.size() == 6) {
                v.row(row.toArray(new Btn[0]));
                row.clear();
            }
        }
        if (!row.isEmpty()) v.row(row.toArray(new Btn[0]));
        return v.row(Btn.cb(Tr.t("set.btn.back"), Cb.of("set:home", id)));
    }

    public View nudges(Channel ch) {
        long id = ch.getId();
        View v = new View().line(Tr.t("nud.title")).blank()
                .line(Tr.t("nud.hint"))
                .blank().line(Tr.t("nud.lead"));
        List<Btn> lead = new ArrayList<>();
        for (int m : new int[]{15, 30, 60}) lead.add(Btn.cb((ch.getNudgeLeadMinutes() == m ? "✅ " : "") + Tr.t("nud.min", m), Cb.of("set:lead", id, m)));
        v.row(lead.toArray(new Btn[0]));
        v.blank().line(Tr.t("nud.gap"));
        List<Btn> gap = new ArrayList<>();
        for (int h : new int[]{3, 6, 12, 24}) gap.add(Btn.cb((ch.getNudgeGapHours() == h ? "✅ " : "") + Tr.t("nud.h", h), Cb.of("set:gap", id, h)));
        v.row(gap.toArray(new Btn[0]));
        return v.row(Btn.cb(Tr.t("set.btn.back"), Cb.of("set:home", id)));
    }

    public View comments(Channel ch, String note) {
        long id = ch.getId();
        View v = new View().line(Tr.t("com.title")).blank();
        if (ch.getDiscussionChatId() != null) v.line(Tr.t("com.active"));
        else v.line(Tr.t("com.inactive"));
        v.blank().line(Tr.t("com.how"))
                .line(Tr.t("com.step1"))
                .line(Tr.t("com.step2"))
                .line(Tr.t("com.step3"));
        if (note != null) v.blank().line(note);
        v.row(Btn.link(Tr.t("com.btn.add"), "https://t.me/" + gateway.botUsername() + "?startgroup=true"));
        v.row(Btn.cb(Tr.t("com.btn.check"), Cb.of("set:comchk", id)));
        return v.row(Btn.cb(Tr.t("set.btn.back"), Cb.of("set:home", id)));
    }

    public View importPrompt(Channel ch) {
        long id = ch.getId();
        return new View().line(Tr.t("imp.title")).blank()
                .line(Tr.t("imp.intro"))
                .line(Tr.t("imp.step1"))
                .line(Tr.t("imp.step2"))
                .line(Tr.t("imp.step3"))
                .blank()
                .line(Tr.t("imp.merge"))
                .row(Btn.cb(Tr.t("btn.cancel"), Cb.of("set:home", id)));
    }

    public View confirm(String question, String yesLabel, String yesData, String noData) {
        return new View().line(question).row(Btn.cb(yesLabel, yesData), Btn.cb(Tr.t("confirm.no"), noData));
    }

    public View notifications(Channel ch, ChannelMember m) {
        long id = ch.getId();
        return new View().line(Tr.t("me.title", Fmt.esc(ch.getTitle()))).blank()
                .line(Tr.t("me.digest", Tr.t(m.isNotifyDigest() ? "me.on" : "me.off"), Fmt.dayShort(ch.getDigestDay()) + " " + Fmt.hour(ch.getDigestHour())))
                .line(Tr.t("me.digest_hint"))
                .line(Tr.t("me.nudges", Tr.t(m.isNotifyNudges() ? "me.on" : "me.off")))
                .line(Tr.t("me.nudges_hint"))
                .row(Btn.cb((m.isNotifyDigest() ? "✅" : "⬜") + " " + Tr.t("me.btn.digest"), Cb.of("me:dig", id)))
                .row(Btn.cb((m.isNotifyNudges() ? "✅" : "⬜") + " " + Tr.t("me.btn.nudges"), Cb.of("me:nud", id)))
                .row(Btn.cb(Tr.t("btn.back"), Cb.of("nav:home", id)));
    }
}
