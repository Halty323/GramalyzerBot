package com.halty.gramalyzerbot.settings;

import com.halty.gramalyzerbot.config.BotProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Bot-wide switches and cached values, stored in the database so they survive restarts. */
@Service
@RequiredArgsConstructor
public class BotSettings {

    public static final String WELCOME_PHOTO = "welcome_photo_file_id";
    public static final String ABOUT_PHOTO = "about_photo_file_id";
    public static final String PUBLIC_REGISTRATION = "public_registration";

    private final BotSettingRepository repo;
    private final BotProperties props;

    public Optional<String> get(String key) { return repo.findById(key).map(BotSetting::getValue); }

    @Transactional
    public void set(String key, String value) { repo.save(new BotSetting(key, value)); }

    public boolean publicRegistration() {
        return get(PUBLIC_REGISTRATION).map(Boolean::parseBoolean).orElse(props.publicRegistration());
    }

    @Transactional
    public void setPublicRegistration(boolean open) { set(PUBLIC_REGISTRATION, Boolean.toString(open)); }
}
