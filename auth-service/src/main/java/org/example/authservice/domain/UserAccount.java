package org.example.authservice.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.common.auth.enums.UserStatus;
import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name="user_accounts")
public class UserAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name ="user_id")
    private Long id;

    @Column(unique = true,nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;

    @Column(nullable = false)
    private Integer failedLoginCount;

    private Instant createdAt;
    private Instant updatedAt;


    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }
    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }
    public UserAccount(String email, String passwordHash) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.status = UserStatus.ACTIVE;
        this.failedLoginCount = 0;
    }

    public void delete(){
        this.status = UserStatus.DELETED;
    }

    public boolean isActive(){
        return this.status == UserStatus.ACTIVE;
    }

    public void increaseFailedLoginCount(){
        this.failedLoginCount++;
        if(this.failedLoginCount >= 5){
            this.status = UserStatus.FROZEN;
        }
    }

    public void onLoginSuccess(){
        this.status = UserStatus.ACTIVE;
        this.failedLoginCount = 0;
    }
    public void onLoginFailure(){
        increaseFailedLoginCount();
    }
}
