package com.halty.gramalyzerbot.bot.ui;

import java.util.List;

/** The persistent keyboard at the bottom of the chat. */
public final class Menu {

    public static final String OVERVIEW = "📊 Overview";
    public static final String TIMES = "⏰ Best times";
    public static final String CONTENT = "💡 What to write";
    public static final String SETTINGS = "⚙️ Settings";

    public static final List<List<String>> KEYBOARD = List.of(List.of(OVERVIEW, TIMES), List.of(CONTENT, SETTINGS));

    private Menu() {}
}
