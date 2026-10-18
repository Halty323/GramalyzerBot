package com.halty.gramalyzerbot.bot.ui;

import com.halty.gramalyzerbot.analytics.Metric;
import com.halty.gramalyzerbot.analytics.Report;
import com.halty.gramalyzerbot.i18n.Tr;
import com.halty.gramalyzerbot.channel.Role;

import java.util.Locale;

/** Human-friendly names for the engine's keys, in the current language. */
public final class Labels {

    private Labels() {}

    public static String kind(String key) {
        String k = "lbl.kind." + key;
        return Tr.has(k) ? Tr.t(k) : "• " + key.toLowerCase(Locale.ROOT);
    }

    public static String kindEmoji(String key) { return kind(key).split(" ")[0]; }

    /** The kind without its emoji: "Photo". */
    public static String kindName(String key) {
        String k = kind(key);
        int space = k.indexOf(' ');
        return space < 0 ? k : k.substring(space + 1);
    }

    public static String length(String key) {
        String k = "lbl.length." + key;
        return Tr.has(k) ? Tr.t(k) : key;
    }

    public static String link(String key) { return Tr.t(key.equals("LINK") ? "lbl.link.yes" : "lbl.link.no"); }

    public static String frequency(String key) {
        return switch (key) {
            case "1" -> Tr.t("lbl.freq.1");
            case "2" -> Tr.t("lbl.freq.2");
            default -> Tr.t("lbl.freq.3");
        };
    }

    public static String window(int days) { return days == 0 ? Tr.t("lbl.window.all") : Tr.n("lbl.window.days", days); }

    public static String windowShort(int days) { return days == 0 ? Tr.t("lbl.window_short.all") : Tr.t("lbl.window_short.days", days); }

    public static String slot(Report.Slot s) { return Fmt.dayShort(s.day()) + " " + Fmt.hourRange(s.hour()); }

    public static String confidence(Report.Confidence c) { return Tr.t("lbl.confidence." + c.name()); }

    public static String topic(String key) { return "#" + Fmt.esc(key); }

    public static String metric(Metric m) { return Tr.t("lbl.metric." + m.name()); }

    public static String formula(Metric m) { return Tr.t("lbl.formula." + m.name()); }

    public static String role(Role r) { return Tr.t("lbl.role." + r.name()); }

    /** "👑 Owner" */
    public static String badge(Role r) { return r.icon() + " " + role(r); }
}
