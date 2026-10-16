package com.halty.gramalyzerbot.post;

import com.halty.gramalyzerbot.bot.ui.Fmt;
import com.halty.gramalyzerbot.channel.AuditService;
import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.channel.ChannelService;
import com.halty.gramalyzerbot.common.UserFacingException;
import com.halty.gramalyzerbot.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Backfills a channel's history from a Telegram Desktop JSON export (bots cannot read old posts). */
@Service
@RequiredArgsConstructor
public class ImportService {

    private static final int CHUNK = 500;
    private static final long CHANNEL_BASE = 1_000_000_000_000L;

    private final PostService postService;
    private final ChannelService channels;
    private final AnalysisService analysis;
    private final AuditService audit;

    public record Result(int created, int updated) {
        public int total() { return created + updated; }
    }

    public Result importExport(Channel ch, User actor, InputStream in) throws IOException {
        if (ch.isDemo()) throw new UserFacingException("Importing is for real channels – the demo already has history.");
        List<ExportParser.ExportedPost> parsed = new ArrayList<>();
        ExportParser.Header header = ExportParser.parse(in, parsed::add);
        verify(ch, header);
        if (parsed.isEmpty())
            throw new UserFacingException("I couldn't find any posts in that file. Export the channel history as <b>JSON</b> "
                    + "(not HTML) from Telegram Desktop and send <code>result.json</code>.");

        int created = 0, updated = 0;
        for (int i = 0; i < parsed.size(); i += CHUNK) {
            int[] r = postService.upsertImported(ch, parsed.subList(i, Math.min(parsed.size(), i + CHUNK)));
            created += r[0];
            updated += r[1];
        }
        ch.setHistoryImported(true);
        channels.save(ch);
        analysis.invalidate(ch.getId());
        audit.record(ch.getId(), actor.getId(), "import", "Imported " + (created + updated) + " posts from an export");
        return new Result(created, updated);
    }

    private static void verify(Channel ch, ExportParser.Header h) {
        if (h.type() != null && !h.type().contains("channel"))
            throw new UserFacingException("That export isn't from a channel (it says “" + Fmt.esc(h.type()) + "”).");
        if (h.id() != null) {
            long a = Math.abs(ch.getTelegramChatId());
            if (h.id() != a && h.id() != a - CHANNEL_BASE)
                throw new UserFacingException("That export is from a different channel" +
                        (h.name() == null ? "" : " (“" + Fmt.esc(h.name()) + "”)") + ". I only import into the channel you're managing.");
        }
    }
}
