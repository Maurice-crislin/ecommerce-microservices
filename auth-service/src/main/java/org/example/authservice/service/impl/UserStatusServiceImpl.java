package org.example.authservice.service.impl;

import lombok.RequiredArgsConstructor;
import org.common.auth.enums.UserStatus;
import org.example.authservice.domain.UserAccount;
import org.example.authservice.repository.UserAccountRepository;
import org.example.authservice.service.UserStatusService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;


// @RequiredArgsConstructor 让 Lombok 对所有final字段作为参数 生成构造器，
// Spring 发现这是唯一构造器，于是使用它进行构造器注入。
@Service
@RequiredArgsConstructor
public class UserStatusServiceImpl implements UserStatusService {

    private final StringRedisTemplate stringRedisTemplate ;
    private final UserAccountRepository userAccountRepository;
    @Value("${spring.jwt.jwt-expiration-ms}")
    private long jwtExpirationMs;

    /**
     *  账号封号
     * @param userId
     */
    public void banUser(Long userId) {
        //1. at mysql
        UserAccount userAccount = userAccountRepository.findById(userId).orElseThrow(()->new IllegalStateException("user not found"));
        userAccount.setStatus(UserStatus.BANNED);
        userAccountRepository.save(userAccount);

        //2. at redis
        stringRedisTemplate.opsForValue().set("user:status:"+String.valueOf(userId), String.valueOf(UserStatus.BANNED));
    }

    /**
     * 解封
     * @param userId
     */
    public void unBanUser(Long userId){
        //1. at mysql
        UserAccount userAccount = userAccountRepository.findById(userId).orElseThrow(()->new IllegalStateException("user not found"));
        userAccount.setStatus(UserStatus.ACTIVE);
        userAccountRepository.save(userAccount);

        //2. at redis
        stringRedisTemplate.delete("user:status:"+String.valueOf(userId));
    }


    public boolean isBannedOrFrozen(Long userId) {
        String value = stringRedisTemplate.opsForValue().get("user:status:" + String.valueOf(userId));
        return String.valueOf(UserStatus.BANNED).equals(value) || String.valueOf(UserStatus.FROZEN).equals(value);
    }

    /**
     * 账号冻结
     */
    public void frozenUser(Long userId) {
        // MySQL 已经在 LoginServiceImpl 中通过 onLoginFailure() 写好了
        // 这里只需要同步 Redis
        stringRedisTemplate.opsForValue().set("user:status:"+String.valueOf(userId), String.valueOf(UserStatus.FROZEN));
    }




    /**
     * jwt立即失效
     */
    public void blackJwtByJti(String jti) {
        stringRedisTemplate.opsForValue().set("token:blacklist:"+jti, "1", jwtExpirationMs, TimeUnit.MILLISECONDS);
    }

    public boolean isBlackedJwt(String jti){
        return stringRedisTemplate.hasKey("token:blacklist:"+jti);
    }

}
