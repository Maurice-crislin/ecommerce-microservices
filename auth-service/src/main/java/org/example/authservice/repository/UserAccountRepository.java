package org.example.authservice.repository;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.example.authservice.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccount,Long> {
    boolean existsByEmail(@NotBlank @Email String email);

    Optional<UserAccount> findByEmail(@NotBlank @Email String email);
}
