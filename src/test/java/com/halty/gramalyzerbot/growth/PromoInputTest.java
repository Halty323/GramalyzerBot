package com.halty.gramalyzerbot.growth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PromoInputTest {

    @Test
    void readsTheNameAndAnOptionalPrice() {
        assertEquals(new PromoInput("Shoutout in @techdaily", 20.0, "$"), PromoInput.parse("Shoutout in @techdaily 20$"));
        assertEquals(new PromoInput("Ads", 45.5, "$"), PromoInput.parse("Ads $45.50"));
        assertEquals(new PromoInput("Пост у Kyiv Life", 900.0, "UAH"), PromoInput.parse("Пост у Kyiv Life 900 uah"));
        assertEquals(new PromoInput("Mention", 250.0, "⭐"), PromoInput.parse("Mention 250 stars"));
        assertEquals(new PromoInput("Story", 2.5, "€"), PromoInput.parse("  Story   2,5 €"));
    }

    @Test
    void numbersThatArentPricesStayInTheName() {
        assertEquals(new PromoInput("Link in my bio", null, null), PromoInput.parse("Link in my bio"));
        assertEquals(new PromoInput("Top 10", null, null), PromoInput.parse("Top 10"));
        assertEquals(new PromoInput("Top 10 tips", null, null), PromoInput.parse("Top 10 tips"));
        assertEquals(new PromoInput("", null, null), PromoInput.parse(null));
    }
}
