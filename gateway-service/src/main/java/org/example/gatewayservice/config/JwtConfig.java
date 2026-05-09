package org.example.gatewayservice.config;

import org.common.auth.security.JwtValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JwtConfig {
    @Value("${jwt.secret}")
    private String jwtSecret;

    @Bean
    public JwtValidator jwtValidator() {
        return new JwtValidator(jwtSecret);
    }
}