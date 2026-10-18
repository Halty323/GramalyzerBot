package com.halty.gramalyzerbot.bot.ui;

import com.halty.gramalyzerbot.i18n.Lang;
import com.halty.gramalyzerbot.i18n.Tr;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MenuTest {

    /** A keyboard sent in one language still works after the person switches to another. */
    @Test
    void buttonsAreRecognisedInEveryLanguage() {
        for (Menu.Action a : Menu.Action.values()) {
            for (String label : Menu.labels(a)) assertEquals(Optional.of(a), Menu.match(label), label);
        }
        assertEquals(Optional.empty(), Menu.match("hello"));
        assertEquals(Optional.empty(), Menu.match(""));
    }

    @Test
    void labelsDontCollide() {
        for (Lang l : Lang.values()) {
            List<List<String>> keyboard = Tr.in(l, Menu::keyboard);
            Set<String> seen = new HashSet<>();
            keyboard.forEach(row -> row.forEach(label -> assertTrue(seen.add(label), l + " repeats " + label)));
            assertEquals(Menu.Action.values().length, seen.size());
        }
        Set<String> everywhere = new HashSet<>();
        for (Menu.Action a : Menu.Action.values()) {
            for (String label : new HashSet<>(Menu.labels(a))) assertTrue(everywhere.add(label), "two buttons share " + label);
        }
    }
}
