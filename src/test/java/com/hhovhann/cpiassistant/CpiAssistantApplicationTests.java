package com.hhovhann.cpiassistant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Checks the Spring wiring only. Startup ingestion is switched off, so this
 * passes without LM Studio: building the model beans makes no network call.
 */
@SpringBootTest(properties = "cpi.ingestion.run-on-startup=false")
class CpiAssistantApplicationTests {

    @Test
    void contextLoads() {
    }
}