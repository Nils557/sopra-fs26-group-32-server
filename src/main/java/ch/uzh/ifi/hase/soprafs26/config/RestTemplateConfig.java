package ch.uzh.ifi.hase.soprafs26.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    // Add this new Bean so Spring always knows how to provide an ObjectMapper!
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}