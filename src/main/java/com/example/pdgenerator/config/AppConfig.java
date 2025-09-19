package com.example.pdgenerator.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    // Provides a Jackson ObjectMapper bean for JSON serialization/deserialization
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
