package org.example.authservice.service.impl;

import lombok.RequiredArgsConstructor;
import org.common.auth.dto.RegisterLoginRequest;
import org.common.auth.dto.TokenPair;
import org.common.auth.enums.UserStatus;
import org.example.authservice.domain.UserAccount;
import org.example.authservice.repository.UserAccountRepository;
import org.example.authservice.service.LoginService;
import org.example.authservice.service.TokenService;
import org.example.authservice.service.UserStatusService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LoginServiceImpl implements LoginService {
    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final UserStatusService userStatusService;

    @Override
    public TokenPair login(RegisterLoginRequest request) {
        UserAccount userAccount = userAccountRepository
                .findByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalStateException("Invalid email"));

        if (!userAccount.isActive()) {
            throw new IllegalStateException("Not active user");
        }

        // Fix: Use matches correctly - compare raw password with stored hash
        if (passwordEncoder.matches(request.getPassword(), userAccount.getPasswordHash())) {
            userAccount.onLoginSuccess();
            userAccountRepository.save(userAccount);

            Long userId = userAccount.getId();
            String accessToken = tokenService.generateAccessToken(userId);
            String refreshToken = tokenService.generateRefreshToken(userId);

            return new TokenPair(accessToken, refreshToken);
        } else {
            userAccount.onLoginFailure();
            userAccountRepository.save(userAccount);
            if(userAccount.getStatus() == UserStatus.FROZEN){
                userStatusService.frozenUser(userAccount.getId());
            }
            throw new IllegalStateException("Invalid password");
        }
    }
}