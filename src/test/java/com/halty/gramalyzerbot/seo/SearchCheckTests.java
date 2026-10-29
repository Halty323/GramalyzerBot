package com.halty.gramalyzerbot.seo;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SearchCheckTests {

    private static List<String> printingPosts() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            out.add(switch (i % 4) {
                case 0 -> "New filament test: which brand prints cleanest #printing";
                case 1 -> "Printer upgrade that fixed my warping filament";
                case 2 -> "Slicer settings for a quieter printer #printing";
                default -> "Weekend project number " + i + " finished";
            });
        }
        return out;
    }

    private static SearchCheck.Check check(SearchCheck.Result r, String id) {
        return r.checks().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void aPrivateChannelWithAVagueNameScoresLowAndGetsIdeasFromItsPosts() {
        SearchCheck.Result r = SearchCheck.check("✨ My Little Corner ✨🌸", null, "", printingPosts());
        assertTrue(r.score() < 50, "score " + r.score());
        assertEquals("poor", r.grade());
        assertEquals(SearchCheck.Status.BAD, check(r, "public").status());
        assertEquals(SearchCheck.Status.BAD, check(r, "title_keyword").status());
        assertEquals(SearchCheck.Status.WARN, check(r, "title_clean").status());
        assertEquals(SearchCheck.Status.BAD, check(r, "description_present").status());
        assertEquals(List.of("filament", "printer", "printing"), r.keywords().stream().map(SearchCheck.Keyword::word).limit(3).sorted().toList());
        assertFalse(r.titleIdeas().isEmpty());
        assertTrue(r.titleIdeas().getFirst().contains("My Little Corner"), r.titleIdeas().toString());
        assertTrue(r.usernameIdeas().stream().allMatch(u -> u.matches("[a-z][a-z0-9_]{4,23}")), r.usernameIdeas().toString());
    }

    @Test
    void aClearNameUsernameAndDescriptionScoreWell() {
        SearchCheck.Result r = SearchCheck.check("Printer Lab", "printerlab",
                "Filament tests, printer upgrades and slicer settings for home 3D printing. New posts every weekday.", printingPosts());
        assertTrue(r.score() >= 80, "score " + r.score() + " " + r.checks());
        assertEquals("good", r.grade());
        assertTrue(r.titleIdeas().isEmpty());
        assertTrue(r.usernameIdeas().isEmpty());
        assertTrue(r.keywords().stream().anyMatch(k -> k.word().equals("printer") && k.inTitle() && k.inDescription()));
    }

    @Test
    void wordsAreMatchedInAnyFormAndTooFewPostsMeanTheWordsAreUnknown() {
        assertTrue(SearchCheck.has("Посылки и доставка", "посылками"));
        assertTrue(SearchCheck.has("@printerlab_daily", "printers"));
        assertFalse(SearchCheck.has("Cooking at home", "printer"));
        assertTrue(SearchCheck.decorated("꧁ BEST DEALS ꧂ ★"));
        assertFalse(SearchCheck.decorated("Morning Markets ☕"));

        SearchCheck.Result r = SearchCheck.check("Printer Lab", "printerlab", "", List.of("one post"));
        assertEquals("title_keyword_unknown", r.checks().get(2).id());
        assertTrue(r.keywords().isEmpty());
    }
}
