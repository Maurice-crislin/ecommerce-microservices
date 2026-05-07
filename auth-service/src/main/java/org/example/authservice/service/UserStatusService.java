package org.example.authservice.service;

// 封号功能
public interface UserStatusService {
    void banUser(Long userId);
    boolean isBanned(Long userId);
}
