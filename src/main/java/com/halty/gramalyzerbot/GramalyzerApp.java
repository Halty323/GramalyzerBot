package com.halty.gramalyzerbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class GramalyzerApp {

	public static void main(String[] args) {
		SpringApplication.run(GramalyzerApp.class, args);
	}

}
