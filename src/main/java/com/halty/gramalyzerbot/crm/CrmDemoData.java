package com.halty.gramalyzerbot.crm;

import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.channel.ChannelService;
import com.halty.gramalyzerbot.post.DemoGenerator;
import com.halty.gramalyzerbot.post.DemoService;
import com.halty.gramalyzerbot.workspace.Models;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * In demo mode the CRM's sample channels (design, tech, makers) have no Telegram chat behind them. The first time
 * their insights are requested they get an analytics twin filled with realistic fake posts about the channel's subject,
 * so the website's insights can be explored without a bot. Does nothing outside demo mode.
 */
@Component
public class CrmDemoData {

    /** Synthetic chat ids live far below real ones and below the bot's own sandbox channels (see DemoService). */
    private static final long BASE = -5_000_000_000_000_000_000L;

    private final boolean demo;
    private final ChannelService channels;
    private final DemoService demoService;

    public CrmDemoData(@Value("${gramalyzer.demo:false}") boolean demo, ChannelService channels, DemoService demoService) {
        this.demo = demo;
        this.channels = channels;
        this.demoService = demoService;
    }

    public synchronized Optional<Channel> ensure(Models.Channel crm) {
        if (!demo) return Optional.empty();
        Optional<Channel> existing = channels.byWorkspaceId(crm.id());
        if (existing.isPresent()) return existing;

        long chatId = BASE - Math.floorMod(crm.id().hashCode(), 1_000_000);
        while (channels.byChatId(chatId).isPresent()) chatId--;
        int subscribers = (int) Math.min(Math.max(crm.subscribers(), 0), Integer.MAX_VALUE);
        String username = crm.handle() == null || crm.handle().isBlank() ? null : crm.handle().replaceFirst("^@", "");

        Channel ch = channels.register(chatId, crm.name(), username, subscribers);
        ch.setDemo(true);
        ch.setTimezone(crm.timezone());
        ch.setTimezoneConfirmed(true);
        ch.setHistoryImported(true);
        ch.setWorkspaceChannelId(crm.id());
        ch = channels.save(ch);
        demoService.populate(ch, crm.id().hashCode(), subscribers,
                DemoGenerator.Theme.guess(crm.id(), crm.name(), crm.description()));
        return Optional.of(ch);
    }
}
