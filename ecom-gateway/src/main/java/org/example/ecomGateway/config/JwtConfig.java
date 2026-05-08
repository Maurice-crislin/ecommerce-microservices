package org.example.ecomGateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.common.auth.security.JwtValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JwtConfig {
    @Value("${spring.jwt.secret}")
    private String jwtSecret;

    @Bean
    public JwtValidator jwtValidator() {
        return new JwtValidator(jwtSecret);
    }
}