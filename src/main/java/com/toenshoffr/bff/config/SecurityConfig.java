package com.toenshoffr.bff.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security is used only as a source of building blocks (CORS, the default
 * security header writers, and the servlet session) — NOT its default oauth2Login/
 * form-login flows. Every request is permitted here; the BFF's own auth endpoints,
 * {@link com.toenshoffr.bff.web.CsrfFilter}, and {@link com.toenshoffr.bff.proxy.ApiProxyController}
 * implement the actual custom BFF protocol described in SPEC.md.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, BffProperties props) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable) // handled by our own double-submit CsrfFilter instead
                .cors(cors -> cors.configurationSource(corsConfigurationSource(props)))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED));
        return http.build();
    }

    private CorsConfigurationSource corsConfigurationSource(BffProperties props) {
        CorsConfiguration config = new CorsConfiguration();
        // Exact match, never a wildcard: the reference implementation's CORS contract.
        config.setAllowedOrigins(List.of(props.getFrontendOrigin()));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
