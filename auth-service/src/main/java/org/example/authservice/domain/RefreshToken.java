package org.example.authservice.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(
        name = "refresh_tokens",
        indexes = {
                @Index(name = "idx_token", columnList = "token"),
                @Index(name = "idx_user_revoked", columnList = "userId, revoked")
        }
)
public class RefreshToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, unique = true, length = 128)
    private String token;

    @Column(nullable = false)
    private Instant expiredAt;

    @Column(nullable = false)
    private boolean revoked;

    private Instant revokedAt;

    public RefreshToken(Long userId, String token, Instant expiredAt) {
        this.userId = userId;
        this.token = token;
        this.expiredAt = expiredAt;
        this.revoked = false;
    }
    
    public void revoke() {
        this.revoked = true;
        this.revokedAt = Instant.now();
    }
    
    public boolean checkValid() {
        if (revoked) {
            return false;
        }
        if (Instant.now().isAfter(expiredAt)) {
            return false;
        }
        return true;
    }
}