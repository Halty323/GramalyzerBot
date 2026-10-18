package com.halty.gramalyzerbot.i18n;

/** The CLDR plural categories of the bot's languages, for whole numbers. */
final class Plurals {

    private Plurals() {}

    static String category(Lang l, long n) {
        long abs = Math.abs(n);
        long mod10 = abs % 10, mod100 = abs % 100;
        return switch (l) {
            case RU, UK -> mod10 == 1 && mod100 != 11 ? "one"
                    : mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14) ? "few" : "many";
            case AR -> abs == 0 ? "zero" : abs == 1 ? "one" : abs == 2 ? "two"
                    : mod100 >= 3 && mod100 <= 10 ? "few" : mod100 >= 11 ? "many" : "other";
            case HI, FA -> abs <= 1 ? "one" : "other";
            case ID, VI -> "other";
            default -> abs == 1 ? "one" : "other";
        };
    }
}
