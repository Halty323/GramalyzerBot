package com.halty.gramalyzerbot.workspace;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class Models {
    private Models() {}
    public record Channel(String id, String name, String handle, String initials, String color,
                          long subscribers, double growth, long views, double engagement, String description,
                          boolean paused, String timezone, boolean notifications, boolean autoDelete, String signature,
                          Long telegramChatId) {}
    /** A picture, video or GIF uploaded for a queued post (see {@link WorkspaceMedia}). kind is photo, video or animation. */
    public record MediaRef(@NotBlank @Size(max=40) String id, @Pattern(regexp="photo|video|animation") String kind, @Size(max=120) String name) {}
    /** A link button under a post. */
    public record LinkButton(@NotBlank @Size(max=40) String text, @NotBlank @Size(max=500) String url) {}
    /**
     * media: up to 10 pictures/videos (2+ go out as an album); buttons: link buttons under the post (not possible on albums);
     * silent: sent without a notification sound; pin: pinned in the channel once sent.
     */
    public record Post(String id, String channelId, String title, String body, String status, Instant scheduledAt,
                       long views, long reactions, long shares, String category,
                       String deliveryState, Long telegramMessageId, String deliveryError,
                       List<MediaRef> media, List<LinkButton> buttons, boolean silent, boolean pin) {
        public Post {
            media = media == null ? List.of() : List.copyOf(media);
            buttons = buttons == null ? List.of() : List.copyOf(buttons);
        }
        /** A text post without media, buttons or options. */
        public Post(String id, String channelId, String title, String body, String status, Instant scheduledAt,
                    long views, long reactions, long shares, String category,
                    String deliveryState, Long telegramMessageId, String deliveryError) {
            this(id, channelId, title, body, status, scheduledAt, views, reactions, shares, category, deliveryState, telegramMessageId, deliveryError, List.of(), List.of(), false, false);
        }
        public Post withDelivery(String status, String deliveryState, Long telegramMessageId, String deliveryError) {
            return new Post(id, channelId, title, body, status, scheduledAt, views, reactions, shares, category, deliveryState, telegramMessageId, deliveryError, media, buttons, silent, pin);
        }
        public Post withText(String title, String body) {
            return new Post(id, channelId, title, body, status, scheduledAt, views, reactions, shares, category, deliveryState, telegramMessageId, deliveryError, media, buttons, silent, pin);
        }
    }
    /** media, buttons, silent and pin left out (null) keep what the post had, so older clients don't wipe them. */
    public record PostInput(@NotBlank String channelId, @NotBlank @Size(max=180) String title,
                            @NotNull @Size(max=4096) String body,
                            @Pattern(regexp="draft|scheduled") @NotNull String status,
                            @NotNull Instant scheduledAt, @Size(max=40) String category,
                            @Size(max=10) List<@Valid @NotNull MediaRef> media, @Size(max=8) List<@Valid @NotNull LinkButton> buttons,
                            Boolean silent, Boolean pin) {
        public PostInput(String channelId, String title, String body, String status, Instant scheduledAt, String category) {
            this(channelId, title, body, status, scheduledAt, category, null, null, null, null);
        }
    }
    public record History(String channelId, String date, long subscribers) {}
    public record Workspace(List<Channel> channels, List<Post> posts, boolean demo, List<History> history, boolean botEnabled) {}
}
