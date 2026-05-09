package org.example.authservice.config;

import org.common.auth.security.JwtValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class SecurityBeansConfig {

    @Value("${spring.jwt.secret}")
    private String jwtSecret;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // @Bean 的作用 = 把 JwtValidator 注册到 Spring 容器，让 @RequiredArgsConstructor 可以自动注入。
    @Bean
    public JwtValidator jwtValidator() {
        return new JwtValidator(jwtSecret);
    }
}