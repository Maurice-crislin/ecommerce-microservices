package org.example.authservice.service;

import org.antlr.v4.runtime.misc.Pair;
import org.common.auth.dto.TokenPair;
import org.example.authservice.domain.RefreshToken;

public interface TokenService {
    String generateAccessToken(Long userId);
    String generateRefreshToken(Long userId);
    Boolean validateJwtToken(String jwtToken);
    TokenPair regenerateBothToken(String refreshToken);
}
