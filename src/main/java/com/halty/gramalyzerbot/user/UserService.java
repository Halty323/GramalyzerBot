package com.halty.gramalyzerbot.user;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    @Transactional
    public User getOrCreate(Long telegramId, String username, String firstName) {

        Optional<User> existingUser =
                userRepository.findByTelegramId(telegramId);

        if (existingUser.isPresent()) {
            return existingUser.get();
        } else {
            User user = new User();
            user.setTelegramId(telegramId);
            user.setUsername(username);
            user.setFirstName(firstName);
            user.setLastSeenAt(Instant.now());
            user.setCreatedAt(Instant.now());

            userRepository.save(user);

            return user;
        }
    }

    public Optional<User> findByTelegramId(Long telegramId) {
        return userRepository.findByTelegramId(telegramId);
    }
}