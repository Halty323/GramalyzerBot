package com.halty.gramalyzerbot.tracking;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Downloads Telegram's public channel pages. Kept apart from {@link PreviewParser} so tests can replace the network.
 * Uses the JVM's proxy settings, like the Telegram client.
 */
@Component
public class PreviewClient {

    /** What a page request returned: the final URL after redirects matters, because t.me/s/ redirects non-channels. */
    public record Page(int status, String url, String html) {}

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .proxy(ProxySelector.getDefault())
            .build();

    /** {@code t.me/s/<username>}, optionally only posts older than {@code before}. */
    public Page preview(String username, Integer before) throws IOException {
        return get("https://t.me/s/" + username + (before == null ? "" : "?before=" + before));
    }

    /** The plain {@code t.me/<username>} page. */
    public Page page(String username) throws IOException {
        return get("https://t.me/" + username);
    }

    private Page get(String url) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                // English labels ("subscribers", "edited") are what the parser reads
                .header("Accept-Language", "en-US,en;q=0.8")
                .header("User-Agent", "Mozilla/5.0 (compatible; GramalyzerBot/0.1; +https://github.com/Halty323/GramalyzerBot)")
                .GET().build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            return new Page(res.statusCode(), res.uri().toString(), res.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + url, e);
        }
    }
}
