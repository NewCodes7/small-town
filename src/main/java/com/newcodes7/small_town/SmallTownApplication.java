package com.newcodes7.small_town;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import jakarta.annotation.PostConstruct;

@EnableConfigurationProperties
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class SmallTownApplication {

	@PostConstruct
	public void init() {
		// JVM 기본 시간대를 한국 시간(Asia/Seoul)으로 설정
		TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
	}

	public static void main(String[] args) {
		SpringApplication.run(SmallTownApplication.class, args);
	}

}
