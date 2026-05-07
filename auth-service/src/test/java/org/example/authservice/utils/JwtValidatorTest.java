package org.example.authservice.utils;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.common.auth.utils.JwtValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JwtValidatorTest {

    private static final String TEST_SECRET = "V3o5L2hXnZsT8O0x5kYbGk1mQ2sR9eZpYvQfHjKlL7I=";
    private JwtValidator jwtValidator;
    private Key signingKey;

    @BeforeEach
    void setUp() {
        jwtValidator = new JwtValidator(TEST_SECRET);
        signingKey = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void shouldValidateAndGetUserIdFromToken() {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + 3600000); // 1 hour later

        String token = Jwts.builder()
                .setSubject("123")
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .compact();

        Long userId = jwtValidator.validateAndGetUserId(token);
        assertEquals(123L, userId);
    }

    @Test
    void shouldRejectExpiredToken() {
        Date past = new Date(System.currentTimeMillis() - 3600000);

        String token = Jwts.builder()
                .setSubject("123")
                .setIssuedAt(new Date(System.currentTimeMillis() - 7200000))
                .setExpiration(past)
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .compact();

        assertThrows(JwtException.class, () -> jwtValidator.validateAndGetUserId(token));
        assertFalse(jwtValidator.validateJwtToken(token));
    }

    @Test
    void shouldRejectInvalidToken() {
        assertFalse(jwtValidator.validateJwtToken("invalid-token"));
        assertFalse(jwtValidator.validateJwtToken("aaa.bbb.ccc"));
    }

    @Test
    void shouldRejectMalformedToken() {
        assertThrows(JwtException.class,
                () -> jwtValidator.validateAndGetUserId("not-a-jwt"));
    }

    @Test
    void shouldThrowOnEmptyToken() {
        assertThrows(IllegalArgumentException.class, () -> jwtValidator.validateJwtToken(""));
        assertThrows(IllegalArgumentException.class, () -> jwtValidator.validateAndGetUserId(""));
        assertThrows(IllegalArgumentException.class, () -> jwtValidator.getSubjectFromJwtToken(""));
        assertThrows(IllegalArgumentException.class, () -> jwtValidator.getIdFromJwtToken(""));
    }

    @Test
    void shouldThrowOnNullToken() {
        assertThrows(IllegalArgumentException.class, () -> jwtValidator.validateJwtToken(null));
        assertThrows(IllegalArgumentException.class, () -> jwtValidator.validateAndGetUserId(null));
    }

    @Test
    void shouldGetSubjectFromToken() {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + 3600000);

        String token = Jwts.builder()
                .setSubject("456")
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .compact();

        // Subject can be extracted after validation
        assertTrue(jwtValidator.validateJwtToken(token));
        String subject = jwtValidator.getSubjectFromJwtToken(token);
        assertEquals("456", subject);
    }

    @Test
    void shouldGetJtiFromToken() {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + 3600000);
        String jti = UUID.randomUUID().toString();

        String token = Jwts.builder()
                .setSubject("789")
                .setId(jti)
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .compact();

        assertTrue(jwtValidator.validateJwtToken(token));
        String extractedJti = jwtValidator.getIdFromJwtToken(token);
        assertEquals(jti, extractedJti);
    }

    @Test
    void shouldReturnNullJtiWhenNotPresent() {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + 3600000);

        String token = Jwts.builder()
                .setSubject("789")
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .compact();

        assertNull(jwtValidator.getIdFromJwtToken(token));
    }
}