package org.example.authservice.service;

import org.common.auth.dto.TokenPair;

public interface TokenService {
    String generateAccessToken(Long userId);
    String generateRefreshToken(Long userId);
    Boolean validateJwtToken(String jwtToken);
    TokenPair regenerateBothToken(String refreshToken);
}