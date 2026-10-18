package com.halty.gramalyzerbot.bot.ui;

import com.halty.gramalyzerbot.i18n.Tr;

import java.util.List;
import java.util.Optional;

/** The persistent keyboard at the bottom of the chat. Its labels follow the person's language. */
public final class Menu {

    /** What a keyboard button does. */
    public enum Action { OVERVIEW, TIMES, CONTENT, SETTINGS, WATCHLIST }

    private Menu() {}

    public static String label(Action a) { return Tr.t("menu." + a.name().toLowerCase(java.util.Locale.ROOT)); }

    /** The keyboard in the current language. */
    public static List<List<String>> keyboard() {
        return List.of(List.of(label(Action.OVERVIEW), label(Action.TIMES)),
                List.of(label(Action.CONTENT), label(Action.SETTINGS)),
                List.of(label(Action.WATCHLIST)));
    }

    /**
     * Which button was tapped. Matches every language, because the keyboard on someone's screen keeps the labels it was
     * sent with until the next one replaces it.
     */
    public static Optional<Action> match(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        for (Action a : Action.values()) {
            for (String label : Tr.all("menu." + a.name().toLowerCase(java.util.Locale.ROOT)).values())
                if (label.equals(text)) return Optional.of(a);
        }
        return Optional.empty();
    }

    /** Every label of one button, for tests. */
    static List<String> labels(Action a) {
        return Tr.all("menu." + a.name().toLowerCase(java.util.Locale.ROOT)).entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey()).map(java.util.Map.Entry::getValue).toList();
    }
}
