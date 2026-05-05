package org.example.authservice.service.impl;

import lombok.RequiredArgsConstructor;
import org.example.authservice.domain.RefreshToken;
import org.example.authservice.exception.AuthException;
import org.example.authservice.repository.RefreshTokenRepository;
import org.example.authservice.security.JwtProvider;
import org.example.authservice.service.LogoutService;
import org.example.authservice.service.TokenService;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LogoutServiceImpl implements LogoutService {
    private final TokenService tokenService;
    private final JwtProvider jwtProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    
    @Override
    public void logout(String jwtToken, String refreshToken) {
        // 验证 JWT 有效
        if(!tokenService.validateJwtToken(jwtToken))
            throw new AuthException("Invalid access token");

        // JWT 解析出的 userId 是 String，但现在需要 Long 类型
        String userIdStr = jwtProvider.getSubjectFromJwtToken(jwtToken);
        Long userId;
        try {
            userId = Long.valueOf(userIdStr);
        } catch (NumberFormatException e) {
            throw new AuthException("Invalid user id in token");
        }
        // 查找 refresh token
        RefreshToken refreshTokenEntity = refreshTokenRepository
                .findByToken(refreshToken)
                .orElseThrow(()-> new AuthException("Invalid refresh token"));

        // 验证 refresh token 属于 JWT 里的用户
        if( !refreshTokenEntity.getUserId().equals(userId)) {
            throw new AuthException("Refresh token doesn't match this user");
        }

        refreshTokenEntity.revoke();

        refreshTokenRepository.save(refreshTokenEntity);
    }
}