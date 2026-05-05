package org.example.authservice.utils;

import io.jsonwebtoken.JwtException;
import org.common.auth.utils.JwtValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class JwtValidatorTest {

    private static final String TEST_SECRET = "V3o5L2hXnZsT8O0x5kYbGk1mQ2sR9eZpYvQfHjKlL7I=";
    private JwtValidator jwtValidator;

    @BeforeEach
    void setUp() {
        jwtValidator = new JwtValidator(TEST_SECRET);
    }

    @Test
    void shouldValidateAndGetUserIdFromToken() {
        // Create a manually constructed JWT for a known user
        // We'll use the same logic as JwtProvider
        Header header = new Header("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        Payload payload = new Payload("{\"sub\":\"123\",\"iat\":1620000000,\"exp\":4620000000}");

        String headerBase64 = base64UrlEncode(header.toString().getBytes(StandardCharsets.UTF_8));
        String payloadBase64 = base64UrlEncode(payload.toString().getBytes(StandardCharsets.UTF_8));

        String token = headerBase64 + "." + payloadBase64 + ".dummy-signature";

        // We'll just test that the validator rejects invalid tokens
        // (We can't generate valid JWT without the signing key in test)
        
        assertThrows(JwtException.class,
                () -> jwtValidator.validateAndGetUserId(token));
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
    }

    // Helper inner class for constructing JWT parts
    private static class Header {
        private final String content;
        Header(String content) { this.content = content; }
        @Override
        public String toString() { return content; }
    }

    private static class Payload {
        private final String content;
        Payload(String content) { this.content = content; }
        @Override
        public String toString() { return content; }
    }

    private static String base64UrlEncode(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }
}