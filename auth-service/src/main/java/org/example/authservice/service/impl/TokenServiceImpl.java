package org.example.authservice.service.impl;

import lombok.RequiredArgsConstructor;
import org.common.auth.dto.TokenPair;
import org.example.authservice.domain.RefreshToken;
import org.example.authservice.repository.RefreshTokenRepository;
import org.example.authservice.security.JwtProvider;
import org.example.authservice.service.TokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class TokenServiceImpl implements TokenService {

    private final JwtProvider jwtProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    
    @Value("${spring.jwt.refresh-expiration-days}")
    private int refreshExpirationDays;

    /**
     * AccessToken 是 jwt
     * 用途	访问受保护资源
     * 撤销	无法轻易撤销（等过期
     * @param userId
     * @return
     */
    @Override
    public String generateAccessToken(Long userId) {
        return jwtProvider.generateJwtToken(String.valueOf(userId));
    }

    /**
     * RefreshToke 是 一个 自定义字符串,要存入数据库表的,用户id-》字符串
     * 用途	刷新获取新的 Access Token
     * 撤销	可以撤销
     * @param userId
     * @return
     */
    @Override
    public String generateRefreshToken(Long userId) {
        String refreshTokenStr = java.util.UUID.randomUUID().toString();
        RefreshToken refreshTokenEntity = new RefreshToken(
                userId,
                refreshTokenStr,
                Instant.now().plus(Duration.ofDays(refreshExpirationDays))
        );

        refreshTokenRepository.save(refreshTokenEntity);
        return refreshTokenStr;
    }

    @Override
    public Boolean validateJwtToken(String jwtToken) {
        return jwtProvider.validateJwtToken(jwtToken);
    }

    @Override
    public TokenPair regenerateBothToken(String oldRefreshToken) {
        RefreshToken oldRefreshTokenEntity = refreshTokenRepository.findByToken(oldRefreshToken)
                .orElseThrow(() -> new IllegalStateException("not valid refresh token"));
        
        if (!oldRefreshTokenEntity.checkValid()) {
            throw new IllegalStateException("refresh token is invalid");
        }

        Long userId = oldRefreshTokenEntity.getUserId();
        String newAccessToken = generateAccessToken(userId);
        String newRefreshToken = generateRefreshToken(userId);
        
        // 撤销旧的refresh token，防止重复使用
        oldRefreshTokenEntity.revoke();
        refreshTokenRepository.save(oldRefreshTokenEntity);
        
        return new TokenPair(newAccessToken, newRefreshToken);
    }
}