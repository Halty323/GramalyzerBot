package com.halty.gramalyzerbot.bot.ui;

import com.halty.gramalyzerbot.analytics.Report;

import java.util.Locale;

/** Human-friendly names for the engine's keys. */
public final class Labels {

    private Labels() {}

    public static String kind(String key) {
        return switch (key) {
            case "TEXT" -> "📝 Text";
            case "PHOTO" -> "🖼 Photo";
            case "VIDEO" -> "🎞 Video";
            case "ANIMATION" -> "🎬 GIF";
            case "AUDIO" -> "🎵 Audio";
            case "VOICE" -> "🎙 Voice";
            case "VIDEO_NOTE" -> "⭕ Video note";
            case "DOCUMENT" -> "📎 File";
            case "POLL" -> "📊 Poll";
            case "ALBUM" -> "🗂 Album";
            case "STICKER" -> "😀 Sticker";
            default -> "• " + key.toLowerCase(Locale.ROOT);
        };
    }

    public static String kindEmoji(String key) { return kind(key).split(" ")[0]; }

    public static String length(String key) {
        return switch (key) {
            case "NONE" -> "No text";
            case "SHORT" -> "Short, up to 120 chars";
            case "MEDIUM" -> "Medium, 121–400 chars";
            case "LONG" -> "Long, 401–800 chars";
            case "XLONG" -> "Very long, 800+ chars";
            default -> key;
        };
    }

    public static String link(String key) { return key.equals("LINK") ? "With a link" : "Without a link"; }

    public static String frequency(String key) {
        return switch (key) {
            case "1" -> "1 post that day";
            case "2" -> "2 posts that day";
            default -> "3+ posts that day";
        };
    }

    public static String window(int days) { return days == 0 ? "all time" : "last " + days + " days"; }

    public static String windowShort(int days) { return days == 0 ? "All" : days + "d"; }

    public static String slot(Report.Slot s) { return Fmt.dayShort(s.day()) + " " + Fmt.hourRange(s.hour()); }

    public static String confidence(Report.Confidence c) {
        return switch (c) {
            case LOW -> "🔴 early data – treat as a hint";
            case MEDIUM -> "🟡 decent data";
            case HIGH -> "🟢 solid data";
        };
    }

    public static String topic(String key) { return "#" + Fmt.esc(key); }
}
