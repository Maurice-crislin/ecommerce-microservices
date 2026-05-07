package org.example.authservice.service.impl;

import lombok.RequiredArgsConstructor;
import org.common.auth.enums.UserStatus;
import org.example.authservice.domain.UserAccount;
import org.example.authservice.repository.UserAccountRepository;
import org.example.authservice.service.UserStatusService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;




// @RequiredArgsConstructor 让 Lombok 对所有final字段作为参数 生成构造器，
// Spring 发现这是唯一构造器，于是使用它进行构造器注入。
@Service
@RequiredArgsConstructor
public class UserStatusServiceImpl implements UserStatusService {

    private final StringRedisTemplate stringRedisTemplate ;
    private final UserAccountRepository userAccountRepository;

    public void banUser(Long userId) {
        //1. at mysql
        UserAccount userAccount = userAccountRepository.findById(userId).orElseThrow(()->new IllegalStateException("user not found"));
        userAccount.setStatus(UserStatus.BANNED);
        userAccountRepository.save(userAccount);

        //2. at redis
        stringRedisTemplate.opsForValue().set("userStatus"+String.valueOf(userId), String.valueOf(UserStatus.BANNED));
    }

    public boolean isBanned(Long userId) {
        String value = stringRedisTemplate.opsForValue().get("userStatus" + String.valueOf(userId));
        return value.equals(String.valueOf(UserStatus.BANNED));
    }

}
