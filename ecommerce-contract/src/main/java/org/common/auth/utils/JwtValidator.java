package org.common.auth.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.security.Key;

/**
 * JWT 验证工具类 - 纯 Java 类，不依赖 Spring
 * 各个微服务通过构造函数传入 secret 来使用
 */
public class JwtValidator {

    private final Key jwtKey;

    public JwtValidator(String jwtSecret) {
        // 在构造函数中初始化密钥
        this.jwtKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 验证 JWT 签名 + 过期时间，并提取 userId
     * 如果无效直接抛出异常
     */
    public Long validateAndGetUserId(String token) {
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("token must not be empty");
        }
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(jwtKey)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            String userIdStr = claims.getSubject();
            return Long.valueOf(userIdStr);
        } catch (JwtException e) {
            throw new JwtException("Invalid token: " + e.getMessage());
        } catch (NumberFormatException e) {
            throw new JwtException("Invalid userId in token");
        }
    }

    /**
     * 仅验证 JWT 是否有效（签名 + 过期时间）
     */
    public boolean validateJwtToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("token must not be empty");
        }
        try {
            Jwts.parserBuilder()
                    .setSigningKey(jwtKey)
                    .build()
                    .parseClaimsJws(token);
            return true;
        } catch (JwtException e) {
            return false;
        }
    }

    /**
     * 从 JWT Payload 中提取 sub 字段（userId）
     * 注意：调用此方法前需要确保 token 已通过 validateJwtToken 验证
     */
    public String getSubjectFromJwtToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("token must not be empty");
        }
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(jwtKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
        return claims.getSubject();
    }
}