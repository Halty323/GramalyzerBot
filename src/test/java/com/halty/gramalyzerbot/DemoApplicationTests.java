package com.halty.gramalyzerbot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@org.springframework.test.context.ActiveProfiles("demo")
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:context;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")
class DemoApplicationTests {

	@Test
	void contextLoads() {
	}

}
