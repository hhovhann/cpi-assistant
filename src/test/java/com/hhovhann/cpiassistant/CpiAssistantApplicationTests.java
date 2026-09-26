package com.hhovhann.cpiassistant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Checks the Spring wiring only. Seeding is off in the test properties, so
 * this passes without LM Studio or the internet: building the beans makes no
 * network call.
 */
@SpringBootTest
class CpiAssistantApplicationTests {

    @Test
    void contextLoads() {
    }
}