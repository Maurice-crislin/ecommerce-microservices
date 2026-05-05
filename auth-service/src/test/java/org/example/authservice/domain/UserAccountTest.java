package org.example.authservice.domain;

import org.common.auth.enums.UserStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class UserAccountTest {

    @Test
    void shouldCreateActiveUserWithZeroFailedLoginCount() {
        UserAccount user = new UserAccount("test@example.com", "hashedPassword");
        
        assertNull(user.getId());
        assertEquals("test@example.com", user.getEmail());
        assertEquals("hashedPassword", user.getPasswordHash());
        assertEquals(UserStatus.ACTIVE, user.getStatus());
        assertEquals(0, user.getFailedLoginCount());
        assertTrue(user.isActive());
    }

    @Test
    void shouldDeleteUser() {
        UserAccount user = new UserAccount("test@example.com", "hashedPassword");
        user.delete();
        
        assertEquals(UserStatus.DELETED, user.getStatus());
        assertFalse(user.isActive());
    }

    @Test
    void shouldFreezeUserAfterFiveFailedLoginAttempts() {
        UserAccount user = new UserAccount("test@example.com", "hashedPassword");
        
        for (int i = 1; i <= 4; i++) {
            user.onLoginFailure();
            assertEquals(UserStatus.ACTIVE, user.getStatus());
            assertEquals(i, user.getFailedLoginCount());
        }
        
        user.onLoginFailure();
        assertEquals(UserStatus.FROZEN, user.getStatus());
        assertEquals(5, user.getFailedLoginCount());
        assertFalse(user.isActive());
    }

    @Test
    void shouldResetFailedLoginCountOnSuccessfulLogin() {
        UserAccount user = new UserAccount("test@example.com", "hashedPassword");
        
        user.onLoginFailure();
        user.onLoginFailure();
        user.onLoginFailure();
        assertEquals(3, user.getFailedLoginCount());
        
        user.onLoginSuccess();
        assertEquals(UserStatus.ACTIVE, user.getStatus());
        assertEquals(0, user.getFailedLoginCount());
        assertTrue(user.isActive());
    }

    @Test
    void shouldSetTimestampsOnPrePersist() {
        UserAccount user = new UserAccount("test@example.com", "hashedPassword");
        user.prePersist();
        
        assertNotNull(user.getCreatedAt());
        assertNotNull(user.getUpdatedAt());
        assertEquals(user.getCreatedAt(), user.getUpdatedAt());
    }

    @Test
    void shouldUpdateTimestampOnPreUpdate() {
        UserAccount user = new UserAccount("test@example.com", "hashedPassword");
        user.prePersist();
        Instant originalUpdatedAt = user.getUpdatedAt();
        
        user.preUpdate();
        
        assertNotNull(user.getUpdatedAt());
    }
}