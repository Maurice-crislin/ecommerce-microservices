package org.example.authservice.service;

// 封号功能
public interface UserStatusService {
    void banUser(Long userId);
    void unBanUser(Long userId);
    boolean isBannedOrFrozen(Long userId);
    void blackJwtByJti(String jti);
    boolean isBlackedJwt(String jti);
    void frozenUser(Long userId);
}
