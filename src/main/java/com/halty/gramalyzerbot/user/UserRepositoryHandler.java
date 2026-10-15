package com.halty.gramalyzerbot.user;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import java.time.Instant;
@Component
@RequiredArgsConstructor
public class UserRepositoryHandler {
    private final UserRepository userRepository;
    public void saveAs(User user, Update update) {
        if (!update.hasMessage() || update.getMessage().getFrom() == null) return;
        var from = update.getMessage().getFrom();
        user.setFirstName(from.getFirstName()); user.setUsername(from.getUserName());
        user.setLastSeenAt(Instant.now());
        if (user.getCreatedAt() == null) user.setCreatedAt(Instant.now());
        user.setTelegramId(from.getId()); userRepository.save(user);
    }
}
