package com.halty.gramalyzerbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.PropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Built-in defaults come from gramalyzer-defaults.properties. Loaded through {@code @PropertySource}, they rank below
 * every application.yml/.properties, profile file, environment variable and command-line argument, so a local
 * (git-ignored) application.yml can override any of them.
 */
@SpringBootApplication
@PropertySource("classpath:gramalyzer-defaults.properties")
@ConfigurationPropertiesScan
@EnableScheduling
public class GramalyzerApp {

    public static void main(String[] args) {
        SpringApplication.run(GramalyzerApp.class, args);
    }

}
