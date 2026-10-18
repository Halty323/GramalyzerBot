package com.halty.gramalyzerbot.bot.ui;

import com.halty.gramalyzerbot.i18n.Tr;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/** Small formatting helpers shared by every screen. Output is Telegram-HTML safe. */
public final class Fmt {

    private static final String SPARK = "▁▂▃▄▅▆▇█";

    private Fmt() {}

    public static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public static String num(long n) { return String.format(Locale.US, "%,d", n); }

    /** 980 → "980", 12_300 → "12.3K", 1_250_000 → "1.3M". */
    public static String compact(long n) {
        if (Math.abs(n) < 1000) return String.valueOf(n);
        if (Math.abs(n) < 1_000_000) return String.format(Locale.US, n < 100_000 ? "%.1fK" : "%.0fK", n / 1000.0).replace(".0K", "K");
        return String.format(Locale.US, "%.1fM", n / 1_000_000.0).replace(".0M", "M");
    }

    public static String signed(long n) { return n > 0 ? "+" + num(n) : n < 0 ? "−" + num(-n) : "±0"; }

    public static String lift(double x) { return String.format(Locale.US, "%.1f×", x); }

    public static String one(double x) { return String.format(Locale.US, "%.1f", x); }

    /** In the current language ({@link Tr}), capitalised: "Tue", "Вт", "Sel". */
    public static String dayShort(DayOfWeek d) { return capital(d.getDisplayName(TextStyle.SHORT_STANDALONE, Tr.locale()).replace(".", "")); }

    public static String dayLong(DayOfWeek d) { return capital(d.getDisplayName(TextStyle.FULL_STANDALONE, Tr.locale())); }

    private static String capital(String s) {
        if (s.isEmpty()) return s;
        int first = s.codePointAt(0);
        return new String(Character.toChars(Character.toTitleCase(first))) + s.substring(Character.charCount(first));
    }

    public static String hour(int h) { return String.format(Locale.ROOT, "%02d:00", h); }

    public static String hourRange(int h) { return hour(h) + "–" + hour((h + 1) % 24); }

    public static String trunc(String s, int max) {
        if (s == null) return "";
        String flat = s.replaceAll("\\s+", " ").trim();
        if (flat.codePointCount(0, flat.length()) <= max) return flat;
        int end = flat.offsetByCodePoints(0, max - 1);
        return flat.substring(0, end).trim() + "…";
    }

    /** Horizontal bar, half-block resolution. value/max in [0,∞). */
    public static String bar(double value, double max, int width) {
        if (max <= 0 || value <= 0) return "";
        double units = Math.min(width, value / max * width);
        int full = (int) units;
        boolean half = units - full >= 0.5;
        return "█".repeat(full) + (half ? "▌" : "");
    }

    public static String progress(int current, int target, int width) {
        int filled = target <= 0 ? width : (int) Math.round(Math.min(1.0, (double) current / target) * width);
        return "▰".repeat(filled) + "▱".repeat(width - filled);
    }

    public static String sparkline(List<? extends Number> values) {
        if (values.isEmpty()) return "";
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (Number v : values) { min = Math.min(min, v.doubleValue()); max = Math.max(max, v.doubleValue()); }
        StringBuilder sb = new StringBuilder();
        for (Number v : values) {
            int idx = max == min ? 3 : (int) Math.round((v.doubleValue() - min) / (max - min) * (SPARK.length() - 1));
            sb.append(SPARK.charAt(idx));
        }
        return sb.toString();
    }

    public static String ago(Instant then, Instant now) {
        if (then == null) return Tr.t("fmt.never");
        Duration d = Duration.between(then, now);
        if (d.isNegative() || d.toMinutes() < 1) return Tr.t("fmt.just_now");
        if (d.toMinutes() < 60) return Tr.t("fmt.minutes_ago", d.toMinutes());
        if (d.toHours() < 48) return Tr.t("fmt.hours_ago", d.toHours());
        return Tr.t("fmt.days_ago", d.toDays());
    }

    public static String rule() { return "━━━━━━━━━━━━━━━━"; }
}
