package com.halty.gramalyzerbot.bot.screen;

import com.halty.gramalyzerbot.analytics.Metric;
import com.halty.gramalyzerbot.bot.BotApi;
import com.halty.gramalyzerbot.bot.ui.*;
import com.halty.gramalyzerbot.channel.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Channel configuration, all driven by buttons (and one typed prompt for the timezone). */
@Component
@RequiredArgsConstructor
public class SettingsScreens {

    private final BotApi gateway;

    public View home(Channel ch, Role role) {
        long id = ch.getId();
        View v = new View().line("⚙️ <b>Settings</b> · " + Fmt.esc(ch.getTitle())).line(Fmt.rule());
        v.line("🌍 Timezone: <b>" + Fmt.esc(TimeZones.label(ch.zone(), Instant.now())) + "</b>" + (ch.isTimezoneConfirmed() ? "" : "  <i>(not set yet)</i>"));
        v.line("📅 Analysis window: <b>" + Labels.window(ch.getWindowDays()) + "</b>");
        v.line("🎯 Success measured by: <b>" + ch.getMetric().label() + "</b> <i>(" + ch.getMetric().formula() + ")</i>");
        v.line("📬 Weekly digest: <b>" + Fmt.dayShort(ch.getDigestDay()) + " " + Fmt.hour(ch.getDigestHour()) + "</b>");
        v.line("⏰ Nudges: <b>" + ch.getNudgeLeadMinutes() + " min</b> before a top slot, if nothing posted for <b>" + ch.getNudgeGapHours() + " h</b>");
        v.line("💬 Comments: " + (ch.getDiscussionChatId() != null ? "<b>tracked</b> ✅" : "<b>not linked</b>"));
        v.row(Btn.cb("🌍 Timezone", Cb.of("set:tz", id)), Btn.cb("📅 Window", Cb.of("set:win", id)));
        v.row(Btn.cb("🎯 Metric", Cb.of("set:met", id)), Btn.cb("📬 Digest time", Cb.of("set:dig", id)));
        v.row(Btn.cb("⏰ Nudge rules", Cb.of("set:nud", id)), Btn.cb("💬 Comments", Cb.of("set:com", id)));
        v.rowIf(role.can(Permission.IMPORT_DATA) && !ch.isDemo(), Btn.cb("📥 Import history", Cb.of("set:import", id)));
        v.row(Btn.cb("🔔 My notifications", Cb.of("me:home", id)));
        if (role.can(Permission.RESET_DATA)) {
            v.row(Btn.cb("🧹 Reset data", Cb.of("set:reset", id)),
                    Btn.cb(ch.isDemo() ? "🗑 Delete demo" : "🔌 Disconnect", Cb.of("set:disc", id)));
        }
        return v.row(Btn.cb("‹ Dashboard", Cb.of("nav:home", id)));
    }

    public View timezonePrompt(Channel ch) {
        long id = ch.getId();
        View v = new View().line("🌍 <b>Timezone</b>")
                .blank()
                .line("Right now: <b>" + Fmt.esc(TimeZones.label(ch.zone(), Instant.now())) + "</b>")
                .blank()
                .line("Type your city (<code>Berlin</code>, <code>New York</code>…) or an offset like <code>UTC+2</code>.")
                .line("Or pick one:");
        List<Btn> row = new ArrayList<>();
        for (String zone : TimeZones.POPULAR) {
            row.add(Btn.cb(TimeZones.cityName(java.time.ZoneId.of(zone)), Cb.of("set:tzset", id, zone)));
            if (row.size() == 2) {
                v.row(row.toArray(new Btn[0]));
                row.clear();
            }
        }
        if (!row.isEmpty()) v.row(row.toArray(new Btn[0]));
        return v.row(Btn.cb("‹ Settings", Cb.of("set:home", id)));
    }

    public View timezoneChoices(Channel ch, List<java.time.ZoneId> zones) {
        View v = new View().line("🌍 I found a few matches – which one?");
        for (java.time.ZoneId z : zones)
            v.row(Btn.cb(TimeZones.label(z, Instant.now()), Cb.of("set:tzset", ch.getId(), z.getId())));
        return v.row(Btn.cb("‹ Back", Cb.of("set:tz", ch.getId())));
    }

    public View window(Channel ch) {
        long id = ch.getId();
        View v = new View().line("📅 <b>Analysis window</b>").blank()
                .line("How far back should I look? Shorter windows react faster to changes; longer ones are steadier.");
        List<Btn> chips = new ArrayList<>();
        for (int d : new int[]{30, 90, 180, 0})
            chips.add(Btn.cb((ch.getWindowDays() == d ? "✅ " : "") + (d == 0 ? "All time" : d + " days"), Cb.of("set:winset", id, d)));
        v.row(chips.subList(0, 2).toArray(new Btn[0]));
        v.row(chips.subList(2, 4).toArray(new Btn[0]));
        return v.row(Btn.cb("‹ Settings", Cb.of("set:home", id)));
    }

    public View metric(Channel ch) {
        long id = ch.getId();
        View v = new View().line("🎯 <b>What counts as success?</b>").blank()
                .line("Telegram doesn't share view counts with bots, so I judge posts by how people react.");
        for (Metric m : Metric.values())
            v.line("• <b>" + m.label() + "</b> – " + m.formula());
        for (Metric m : Metric.values())
            v.row(Btn.cb((ch.getMetric() == m ? "✅ " : "") + m.label(), Cb.of("set:metset", id, m.name())));
        return v.row(Btn.cb("‹ Settings", Cb.of("set:home", id)));
    }

    public View digest(Channel ch) {
        long id = ch.getId();
        View v = new View().line("📬 <b>Weekly digest time</b>").blank()
                .line("Sent in <b>" + Fmt.esc(TimeZones.label(ch.zone(), Instant.now())) + "</b> to team members who have it switched on (🔔 My notifications).")
                .blank().line("<b>Day</b>");
        List<Btn> days = new ArrayList<>();
        for (DayOfWeek d : DayOfWeek.values())
            days.add(Btn.cb((ch.getDigestDay() == d ? "✅ " : "") + Fmt.dayShort(d), Cb.of("set:dday", id, d.name())));
        v.row(days.subList(0, 4).toArray(new Btn[0]));
        v.row(days.subList(4, 7).toArray(new Btn[0]));
        v.blank().line("<b>Hour</b>");
        List<Btn> row = new ArrayList<>();
        for (int h = 6; h <= 22; h++) {
            row.add(Btn.cb((ch.getDigestHour() == h ? "✅" : "") + String.format("%02d", h), Cb.of("set:dhour", id, h)));
            if (row.size() == 6) {
                v.row(row.toArray(new Btn[0]));
                row.clear();
            }
        }
        if (!row.isEmpty()) v.row(row.toArray(new Btn[0]));
        return v.row(Btn.cb("‹ Settings", Cb.of("set:home", id)));
    }

    public View nudges(Channel ch) {
        long id = ch.getId();
        View v = new View().line("⏰ <b>Nudge rules</b>").blank()
                .line("A nudge is a short heads-up shortly before one of your best slots – only if you haven't posted for a while. "
                        + "Switch it on for yourself under 🔔 My notifications.")
                .blank().line("<b>Heads-up before the slot</b>");
        List<Btn> lead = new ArrayList<>();
        for (int m : new int[]{15, 30, 60}) lead.add(Btn.cb((ch.getNudgeLeadMinutes() == m ? "✅ " : "") + m + " min", Cb.of("set:lead", id, m)));
        v.row(lead.toArray(new Btn[0]));
        v.blank().line("<b>Stay quiet if I posted in the last</b>");
        List<Btn> gap = new ArrayList<>();
        for (int h : new int[]{3, 6, 12, 24}) gap.add(Btn.cb((ch.getNudgeGapHours() == h ? "✅ " : "") + h + " h", Cb.of("set:gap", id, h)));
        v.row(gap.toArray(new Btn[0]));
        return v.row(Btn.cb("‹ Settings", Cb.of("set:home", id)));
    }

    public View comments(Channel ch, String note) {
        long id = ch.getId();
        View v = new View().line("💬 <b>Comments tracking</b>").blank();
        if (ch.getDiscussionChatId() != null) v.line("✅ <b>Active.</b> I count the comments under each post and use them as a signal.");
        else v.line("⬜ <b>Not linked.</b> Comments are a strong sign of what resonates, so they're worth tracking.");
        v.blank().line("<b>How to set it up</b>")
                .line("1️⃣ Link a discussion group to your channel (channel settings → Discussion)")
                .line("2️⃣ Add me to that group as an <b>admin</b> so I can see messages")
                .line("3️⃣ Tap <b>Check again</b>");
        if (note != null) v.blank().line(note);
        v.row(Btn.link("➕ Add me to a group", "https://t.me/" + gateway.botUsername() + "?startgroup=true"));
        v.row(Btn.cb("🔄 Check again", Cb.of("set:comchk", id)));
        return v.row(Btn.cb("‹ Settings", Cb.of("set:home", id)));
    }

    public View importPrompt(Channel ch) {
        long id = ch.getId();
        return new View().line("📥 <b>Import history</b>").blank()
                .line("Bots can't read old posts, but Telegram Desktop can export them:")
                .line("1️⃣ Open your channel in <b>Telegram Desktop</b> → ⋮ → <b>Export chat history</b>")
                .line("2️⃣ Format: <b>JSON</b>. Media isn't needed – untick everything")
                .line("3️⃣ Send me the <code>result.json</code> file here <i>(max 20 MB)</i>")
                .blank()
                .line("I merge it with what I've already collected, so nothing is counted twice.")
                .row(Btn.cb("✖ Cancel", Cb.of("set:home", id)));
    }

    public View confirm(String question, String yesLabel, String yesData, String noData) {
        return new View().line(question).row(Btn.cb(yesLabel, yesData), Btn.cb("‹ No, go back", noData));
    }

    public View notifications(Channel ch, ChannelMember m) {
        long id = ch.getId();
        return new View().line("🔔 <b>My notifications</b> · " + Fmt.esc(ch.getTitle())).blank()
                .line("📬 <b>Weekly digest</b> – " + (m.isNotifyDigest() ? "ON" : "OFF") + " · " + Fmt.dayShort(ch.getDigestDay()) + " " + Fmt.hour(ch.getDigestHour()))
                .line("   A short recap of last week and what to aim for.")
                .line("⏰ <b>Posting nudges</b> – " + (m.isNotifyNudges() ? "ON" : "OFF"))
                .line("   A heads-up before one of your best slots.")
                .row(Btn.cb((m.isNotifyDigest() ? "✅" : "⬜") + " Weekly digest", Cb.of("me:dig", id)))
                .row(Btn.cb((m.isNotifyNudges() ? "✅" : "⬜") + " Posting nudges", Cb.of("me:nud", id)))
                .row(Btn.cb("‹ Back", Cb.of("nav:home", id)));
    }
}
