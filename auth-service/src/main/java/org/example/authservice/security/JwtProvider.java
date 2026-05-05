package org.example.authservice.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.example.authservice.domain.UserAccount;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;

@Component
public class JwtProvider {

    @Value("${spring.jwt.secret}")
    private String jwtSecret;

    // valid time (ms)
    @Value("${spring.jwt.jwt-expiration-ms}")
    private long jwtExpirationMs;

    private Key jwtKey;

    private final SignatureAlgorithm  signatureAlgorithm = SignatureAlgorithm.HS256;

    @PostConstruct
    public void init() {
        this.jwtKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }


    /**
     * 生成 完整三部分 Header.Payload.Signature
     * @param subject
     * @return
     */
    public String generateJwtToken(String subject) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpirationMs);
        return Jwts.builder()
                .setSubject(subject)
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .signWith(jwtKey,signatureAlgorithm)
                .compact();

    }

    public boolean validateJwtToken(String token) {
        try{
            Jwts.parserBuilder().setSigningKey(jwtKey).build().parseClaimsJws(token);
            return true;
        } catch (JwtException e) {
            return false;
        }
    }
    public String getSubjectFromJwtToken(String token) {
        Claims claims = Jwts.parserBuilder().setSigningKey(jwtKey).build().parseClaimsJws(token).getBody();

        return claims.getSubject();
    }
}
