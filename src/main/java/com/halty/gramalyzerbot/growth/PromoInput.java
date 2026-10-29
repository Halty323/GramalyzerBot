package com.halty.gramalyzerbot.growth;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What someone typed for a new promo link: a name, optionally followed by what the promo cost.
 * "Shoutout in @techdaily 20$", "Ads $45", "Bio link", "Post in Kyiv Life 900 UAH", "Mention 250 stars".
 */
public record PromoInput(String name, Double cost, String currency) {

    private static final Pattern PRICE = Pattern.compile(
            "^(.+?)\\s+([$€£₴]?)\\s*(\\d{1,9}(?:[.,]\\d{1,2})?)\\s*([$€£₴⭐]|[A-Za-z]{3,5})?$", Pattern.UNICODE_CASE);

    public static PromoInput parse(String text) {
        String t = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        Matcher m = PRICE.matcher(t);
        if (!m.matches()) return new PromoInput(t, null, null);
        String before = m.group(2), after = m.group(4);
        // a bare number at the end with no currency could be part of the name ("Top 10"), unless it clearly looks like money
        if (before.isEmpty() && after == null) return new PromoInput(t, null, null);
        String currency = !before.isEmpty() ? before : currency(after);
        if (currency == null) return new PromoInput(t, null, null); // "Top 10 tips": "tips" is not a currency
        return new PromoInput(m.group(1).strip(), Double.parseDouble(m.group(3).replace(',', '.')), currency);
    }

    private static String currency(String word) {
        String w = word.toUpperCase(Locale.ROOT);
        return switch (w) {
            case "$", "€", "£", "₴" -> w;
            case "⭐", "STAR", "STARS" -> "⭐";
            case "USD", "EUR", "UAH", "GBP", "USDT", "TON", "INR", "IDR", "BRL", "TRY", "VND", "UZS", "IRR", "KZT", "PLN", "RUB" -> w;
            default -> null;
        };
    }
}
