package org.example.authservice.repository;

import org.example.authservice.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    void deleteByToken(String token);

    Optional<RefreshToken> findByToken(String token);
    
    List<RefreshToken> findByUserId(Long userId);
    
    List<RefreshToken> findByUserIdAndRevokedFalse(Long userId);
}