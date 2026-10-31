package com.halty.gramalyzerbot.seo;

import com.halty.gramalyzerbot.bot.BotApi;
import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.channel.ChannelService;
import com.halty.gramalyzerbot.workspace.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Search positions the owner looked up themselves: the only honest way to see whether search changes helped. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:searchpos;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "gramalyzer.demo=false", "gramalyzer.api-token=test-only-workspace-token", "telegram.bot.enabled=false", "gramalyzer.tracking.refresh-enabled=false"})
class SearchPositionTests {

    @Autowired SearchCheckService search;
    @Autowired ChannelService channels;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean BotApi bot;

    private Channel ch;

    @BeforeEach
    void reset() {
        for (String table : new String[]{"search_positions", "posts", "channels"}) jdbc.update("DELETE FROM " + table);
        ch = channels.register(-100777L, "Printer Lab", "printerlab", 800);
    }

    @Test
    void eachWordShowsItsLatestPositionAndTheOneBefore() throws InterruptedException {
        search.note(ch, "printer", 14);
        Thread.sleep(5);
        search.note(ch, "filament", null);
        Thread.sleep(5);
        List<SearchCheck.Position> list = search.note(ch, " Printer ", 6);

        assertEquals(2, list.size());
        assertEquals("Printer", list.get(0).word());
        assertEquals(6, list.get(0).position());
        assertEquals(14, list.get(0).previous());
        assertEquals("filament", list.get(1).word());
        assertNull(list.get(1).position(), "not in the results");
        assertEquals(2, search.check(ch).positions().size());
    }

    @Test
    void badNotesAreRefused() {
        assertThrows(WorkspaceService.Problem.class, () -> search.note(ch, "  ", 3));
        assertThrows(WorkspaceService.Problem.class, () -> search.note(ch, "printer", 0));
        assertTrue(search.positions(ch).isEmpty());
    }
}
