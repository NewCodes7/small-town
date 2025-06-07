package com.newcodes7.small_town;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@EnableJpaAuditing
@SpringBootApplication
public class SmallTownApplication {

	public static void main(String[] args) {
		SpringApplication.run(SmallTownApplication.class, args);
	}

}
