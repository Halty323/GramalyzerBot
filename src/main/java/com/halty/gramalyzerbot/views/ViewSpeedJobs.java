package com.halty.gramalyzerbot.views;

import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.channel.ChannelService;
import com.halty.gramalyzerbot.tracking.PreviewClient;
import com.halty.gramalyzerbot.tracking.PreviewParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

/**
 * Reads the web preview of every public own channel that has a post just past a mark (1, 6 or 24 hours old), so each
 * post's views are caught close to each mark. A channel with nothing due isn't fetched at all.
 */
@Component
@ConditionalOnProperty(name = "gramalyzer.tracking.refresh-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class ViewSpeedJobs {

    private final ChannelService channels;
    private final ViewSpeedService speed;
    private final PreviewClient client;

    @Scheduled(fixedDelayString = "${gramalyzer.views.check-every:PT10M}", initialDelayString = "PT2M")
    public void readDue() {
        Instant now = Instant.now();
        for (Channel ch : channels.trackable()) {
            if (ch.getUsername() == null || ch.getUsername().isBlank() || !speed.due(ch, now)) continue;
            try {
                PreviewClient.Page page = client.preview(ch.getUsername(), null);
                if (page.status() != 200) continue;
                PreviewParser.preview(page.html(), ch.getUsername()).ifPresent(p -> speed.record(ch, p.posts(), Instant.now()));
                Thread.sleep(1500); // t.me is a public website, not an API
            } catch (IOException | RuntimeException e) {
                log.warn("Reading views of @{} failed: {}", ch.getUsername(), e.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
