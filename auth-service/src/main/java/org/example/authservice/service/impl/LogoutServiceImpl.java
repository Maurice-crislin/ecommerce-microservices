package org.example.authservice.service.impl;

import lombok.RequiredArgsConstructor;
import org.common.auth.security.JwtValidator;
import org.example.authservice.domain.RefreshToken;
import org.example.authservice.exception.AuthException;
import org.example.authservice.repository.RefreshTokenRepository;
import org.example.authservice.service.LogoutService;
import org.example.authservice.service.UserStatusService;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LogoutServiceImpl implements LogoutService {
    private final JwtValidator jwtValidator;
    private final UserStatusService userStatusService;
    private final RefreshTokenRepository refreshTokenRepository;

    // 目前双token可以做到精细控制退出哪个设备
    @Override
    public void logout(String jwtToken, String refreshToken) {

        // 使用 JwtValidator 先验证 jwt, 之后从 JWT 中提取 userId
        Long userId = jwtValidator.validateAndGetUserId(jwtToken);

        // 查找 refresh token
        RefreshToken refreshTokenEntity = refreshTokenRepository
                .findByToken(refreshToken)
                .orElseThrow(() -> new AuthException("Invalid refresh token"));

        // 验证 refresh token 属于 JWT 里的用户
        if (!refreshTokenEntity.getUserId().equals(userId)) {
            throw new AuthException("Refresh token doesn't match this user");
        }

        // 1.revoke refreshtoken
        refreshTokenEntity.revoke();
        refreshTokenRepository.save(refreshTokenEntity);

        // 2.black jwt
        String jti = jwtValidator.getIdFromJwtToken(jwtToken);
        userStatusService.blackJwtByJti(jti);

    }
}