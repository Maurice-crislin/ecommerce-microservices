package org.example.ecomGateway.security;

import lombok.RequiredArgsConstructor;
import org.common.auth.enums.UserStatus;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 从 Redis 检查 token 黑名单 + 账号状态
 */
@Component
@RequiredArgsConstructor
public class RedisTokenValidator implements RedisTokenService {

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean isBannedOrFrozen(Long userId) {
        String value = stringRedisTemplate.opsForValue().get("user:status:" + userId);
        return String.valueOf(UserStatus.BANNED).equals(value) || String.valueOf(UserStatus.FROZEN).equals(value);
    }

    @Override
    public boolean isBlackedJwt(String jti) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey("token:blacklist:" + jti));
    }
}
