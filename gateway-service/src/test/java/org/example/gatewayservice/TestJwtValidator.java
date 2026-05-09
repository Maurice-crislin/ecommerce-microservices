package org.example.gatewayservice;

import io.jsonwebtoken.JwtException;
import org.common.auth.security.JwtValidator;

/**
 * Test-only JwtValidator that completely bypasses JJWT parsing.
 * Overrides all methods that would invoke JJWT parserBuilder.
 */
class TestJwtValidator extends JwtValidator {

    private static final String VALID_TOKEN = "VALID_JWT_PLACEHOLDER";
    private static final String DUMMY_JTI = "test-jti-12345";

    TestJwtValidator() {
        super("V3o5L2hXnZsT8O0x5kYbGk1mQ2sR9eZpYvQfHjKlL7I=");
    }

    @Override
    public Long validateAndGetUserId(String token) {
        if (VALID_TOKEN.equals(token)) {
            return 123L;
        }
        throw new JwtException("Invalid JWT (test)");
    }

    @Override
    public boolean validateJwtToken(String token) {
        return VALID_TOKEN.equals(token);
    }

    @Override
    public String getIdFromJwtToken(String token) {
        if (VALID_TOKEN.equals(token)) {
            return DUMMY_JTI;
        }
        throw new JwtException("Invalid JWT (test)");
    }

    @Override
    public String getSubjectFromJwtToken(String token) {
        if (VALID_TOKEN.equals(token)) {
            return "123";
        }
        throw new JwtException("Invalid JWT (test)");
    }
}