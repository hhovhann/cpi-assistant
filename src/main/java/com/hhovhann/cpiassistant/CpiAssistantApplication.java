package com.hhovhann.cpiassistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CpiAssistantApplication {

    static void main(String[] args) {
        SpringApplication.run(CpiAssistantApplication.class, args);
    }
}