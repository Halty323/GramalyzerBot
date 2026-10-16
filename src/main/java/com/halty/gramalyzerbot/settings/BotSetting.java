package com.halty.gramalyzerbot.settings;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Tiny key/value store for bot-wide switches and cached values. */
@Entity
@Table(name = "bot_settings")
@Getter
@Setter
@NoArgsConstructor
public class BotSetting {

    @Id
    @Column(name = "setting_key")
    private String key;

    @Column(name = "setting_value")
    private String value;

    public BotSetting(String key, String value) {
        this.key = key;
        this.value = value;
    }
}
