package org.example.authservice.service.impl;

import lombok.RequiredArgsConstructor;
import org.common.auth.utils.JwtValidator;
import org.example.authservice.domain.RefreshToken;
import org.example.authservice.exception.AuthException;
import org.example.authservice.repository.RefreshTokenRepository;
import org.example.authservice.service.LogoutService;
import org.example.authservice.service.TokenService;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LogoutServiceImpl implements LogoutService {
    private final TokenService tokenService;
    private final JwtValidator jwtValidator;
    private final RefreshTokenRepository refreshTokenRepository;
    
    @Override
    public void logout(String jwtToken, String refreshToken) {
        // 验证 JWT 有效
        if (!tokenService.validateJwtToken(jwtToken))
            throw new AuthException("Invalid access token");

        // 使用 JwtValidator 从 JWT 中提取 userId（验证通过后调用）
        Long userId = jwtValidator.validateAndGetUserId(jwtToken);

        // 查找 refresh token
        RefreshToken refreshTokenEntity = refreshTokenRepository
                .findByToken(refreshToken)
                .orElseThrow(() -> new AuthException("Invalid refresh token"));

        // 验证 refresh token 属于 JWT 里的用户
        if (!refreshTokenEntity.getUserId().equals(userId)) {
            throw new AuthException("Refresh token doesn't match this user");
        }

        refreshTokenEntity.revoke();
        refreshTokenRepository.save(refreshTokenEntity);
    }
}