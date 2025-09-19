package com.example.pdgenerator.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Configuration class to enable and customize CORS (Cross-Origin Resource Sharing)
 * settings for the application.
 *
 * This configuration allows cross-origin requests to endpoints under /api/**
 * from any origin, enabling GET, POST, and OPTIONS HTTP methods.
 */
@Configuration
public class WebConfig {

    /**
     * Defines a WebMvcConfigurer bean to customize CORS mappings.
     *
     * @return a WebMvcConfigurer instance with CORS mappings configured
     */
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            
            /**
             * Configure CORS mappings for REST API endpoints.
             *
             * @param registry the CorsRegistry to add mappings to
             */
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")         // Apply CORS rules to all /api paths
                        .allowedOrigins("*")           // Allow requests from any origin (use specific domains in production)
                        .allowedMethods("GET", "POST", "OPTIONS")  // Allowed HTTP methods
                        .allowedHeaders("*");          // Allow all headers
            }
        };
    }
}
