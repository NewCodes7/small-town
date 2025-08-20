package com.newcodes7.small_town;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource("classpath:application-test.properties")
class SmallTownApplicationTests {
	@Test
	void contextLoads() {
		// This test will simply check if the application context loads successfully.
	}
}
