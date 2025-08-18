package com.newcodes7.small_town;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableConfigurationProperties
@SpringBootApplication
@EnableScheduling
public class SmallTownApplication {

	public static void main(String[] args) {
		SpringApplication.run(SmallTownApplication.class, args);
	}

}
