package ch.uzh.ifi.hase.soprafs26.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.session.HttpSessionEventPublisher;

@Configuration
public class WebSecurityConfig {

    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // 1. Disable CSRF globally (Recommended for testing/simplicity in this project)
            .csrf(csrf -> csrf.disable()) 
            
            // 2. Allow frames from the same origin (Essential for H2 Console)
            .headers(headers -> headers
                .frameOptions(frame -> frame.sameOrigin())
            )
            
            // 3. Define who can access what
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/users/**", "/lobbies/**").permitAll() 
                .requestMatchers("/h2-console/**").permitAll()
                .requestMatchers("/ws/**").permitAll()
                .anyRequest().authenticated()
            )
            
            // 4. Enable Basic Auth (This is what triggers the login popup)
            .httpBasic(Customizer.withDefaults());

        return http.build();
    }
}