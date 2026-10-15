package com.halty.gramalyzerbot.workspace;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class Models {
    private Models() {}
    public record Channel(String id, String name, String handle, String initials, String color,
                          long subscribers, double growth, long views, double engagement, String description,
                          boolean paused, String timezone, boolean notifications, boolean autoDelete, String signature,
                          Long telegramChatId) {}
    public record Post(String id, String channelId, String title, String body, String status, Instant scheduledAt,
                       long views, long reactions, long shares, String category,
                       String deliveryState, Long telegramMessageId, String deliveryError) {}
    public record PostInput(@NotBlank String channelId, @NotBlank @Size(max=180) String title,
                            @NotBlank @Size(max=4096) String body,
                            @Pattern(regexp="draft|scheduled") @NotNull String status,
                            @NotNull Instant scheduledAt, @Size(max=40) String category) {}
    public record History(String channelId, String date, long subscribers) {}
    public record Workspace(List<Channel> channels, List<Post> posts, boolean demo, List<History> history, boolean botEnabled) {}
}
