package com.halty.gramalyzerbot.bot.ui;

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

    public static String signed(long n) { return n > 0 ? "+" + num(n) : n < 0 ? "−" + num(-n) : "±0"; }

    public static String lift(double x) { return String.format(Locale.US, "%.1f×", x); }

    public static String one(double x) { return String.format(Locale.US, "%.1f", x); }

    public static String dayShort(DayOfWeek d) { return d.getDisplayName(TextStyle.SHORT, Locale.ENGLISH); }

    public static String dayLong(DayOfWeek d) { return d.getDisplayName(TextStyle.FULL, Locale.ENGLISH); }

    public static String hour(int h) { return String.format(Locale.ROOT, "%02d:00", h); }

    public static String hourRange(int h) { return hour(h) + "–" + hour((h + 1) % 24); }

    public static String plural(long n, String one, String many) { return num(n) + " " + (n == 1 ? one : many); }

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
        if (then == null) return "never";
        Duration d = Duration.between(then, now);
        if (d.isNegative() || d.toMinutes() < 1) return "just now";
        if (d.toMinutes() < 60) return d.toMinutes() + "m ago";
        if (d.toHours() < 48) return d.toHours() + "h ago";
        return d.toDays() + "d ago";
    }

    public static String rule() { return "━━━━━━━━━━━━━━━━"; }
}
