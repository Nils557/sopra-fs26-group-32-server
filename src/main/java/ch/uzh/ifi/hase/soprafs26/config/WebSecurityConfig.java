package ch.uzh.ifi.hase.soprafs26.config;

import java.util.Arrays;
import java.util.Collections;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class WebSecurityConfig {

    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(Customizer.withDefaults())
            .csrf(csrf -> csrf.disable())
            .headers(headers -> headers.frameOptions(frameOptions -> frameOptions.sameOrigin()))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/users/**", "/lobbies/**").permitAll()
                .requestMatchers("/h2-console/**").permitAll()
                .requestMatchers("/ws/**").permitAll()
                .anyRequest().authenticated()
            )
            .httpBasic(Customizer.withDefaults());

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

        // H2 console: allow any origin so it can be opened directly in a browser
        CorsConfiguration h2Config = new CorsConfiguration();
        h2Config.addAllowedOriginPattern("*");
        h2Config.setAllowedMethods(Arrays.asList("GET", "POST", "OPTIONS"));
        h2Config.addAllowedHeader("*");
        source.registerCorsConfiguration("/h2-console/**", h2Config);

        // API: restrict to known front-end origins
        CorsConfiguration apiConfig = new CorsConfiguration();
        apiConfig.setAllowedOrigins(Arrays.asList(
            "http://localhost:3000",
            "https://sopra-fs26-group-32-client.oa.r.appspot.com",
            "https://sopra-fs26-group-32-client.vercel.app"
        ));
        apiConfig.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        apiConfig.setAllowedHeaders(Collections.singletonList("*"));
        apiConfig.setExposedHeaders(Arrays.asList("Authorization", "x-auth-token"));
        apiConfig.setAllowCredentials(true);
        source.registerCorsConfiguration("/**", apiConfig);

        return source;
    }
}
