package org.example.inventoryservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@Configuration
public class RedisLuaConfig {

    @Bean
    public DefaultRedisScript<Long> lockScript() {
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setLocation(new ClassPathResource("lua/lock.lua"));
        redisScript.setResultType(Long.class);
        return redisScript;
    }
    @Bean
    public DefaultRedisScript<Long> confirmScript() {
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setLocation(new ClassPathResource("lua/confirm.lua"));
        redisScript.setResultType(Long.class);
        return redisScript;
    }
    @Bean
    public DefaultRedisScript<Long> unlockScript() {
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setLocation(new ClassPathResource("lua/unlock.lua"));
        redisScript.setResultType(Long.class);
        return redisScript;
    }
}
