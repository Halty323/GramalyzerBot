package com.halty.gramalyzerbot.user;

import com.halty.gramalyzerbot.config.BotProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository users;
    private final BotProperties props;

    /** A prompt the person has started but not finished. */
    public record Pending(String state, String arg) {}

    /** Creates the user on first contact, otherwise refreshes name/last-seen. */
    @Transactional
    public User touch(long telegramId, String username, String firstName, String languageCode) {
        User u = users.findByTelegramId(telegramId).orElseGet(() -> {
            User n = new User();
            n.setTelegramId(telegramId);
            n.setCreatedAt(Instant.now());
            return n;
        });
        u.setUsername(username);
        u.setFirstName(firstName);
        if (languageCode != null) u.setLanguageCode(languageCode); // updates without a sender language (e.g. being added to a channel) keep the last one
        u.setLastSeenAt(Instant.now());
        if (props.isAdmin(telegramId, username)) u.setBotAdmin(true);
        return users.save(u);
    }

    public Optional<User> findByTelegramId(long telegramId) { return users.findByTelegramId(telegramId); }

    public Optional<User> findById(Long id) { return users.findById(id); }

    @Transactional
    public void setState(User u, String state, String arg, Duration ttl) {
        u.setState(state);
        u.setStateArg(arg);
        u.setStateExpiresAt(Instant.now().plus(ttl));
        users.save(u);
    }

    @Transactional
    public void clearState(User u) {
        if (u.getState() == null) return;
        u.setState(null);
        u.setStateArg(null);
        u.setStateExpiresAt(null);
        users.save(u);
    }

    public Optional<Pending> pending(User u) {
        if (u.getState() == null) return Optional.empty();
        if (u.getStateExpiresAt() != null && u.getStateExpiresAt().isBefore(Instant.now())) return Optional.empty();
        return Optional.of(new Pending(u.getState(), u.getStateArg()));
    }

    @Transactional
    public void setActiveChannel(User u, Long channelId) {
        u.setActiveChannelId(channelId);
        users.save(u);
    }

    /** @param code a supported language code, or null to follow the Telegram app's language again */
    @Transactional
    public void setLanguage(User u, String code) {
        u.setLanguage(code);
        users.save(u);
    }

    @Transactional
    public void makeBotAdmin(User u) {
        u.setBotAdmin(true);
        users.save(u);
    }

    @Transactional
    public Optional<User> setBanned(long telegramId, boolean banned) {
        return users.findByTelegramId(telegramId).map(u -> {
            u.setBanned(banned);
            return users.save(u);
        });
    }

    public boolean anyBotAdmin() { return !props.admins().isEmpty() || !users.findAllByBotAdminTrue().isEmpty(); }

    /** Everyone who can open /admin and has talked to the bot (so it can message them). */
    public List<User> botAdmins() { return users.findAllByBotAdminTrue(); }

    public long count() { return users.count(); }

    public long bannedCount() { return users.countByBannedTrue(); }

    public List<User> page(int page, int size) {
        Page<User> p = users.findAllByOrderByLastSeenAtDesc(PageRequest.of(page, size));
        return p.getContent();
    }
}
