package org.example.authservice.service.impl;

import lombok.RequiredArgsConstructor;
import org.common.auth.dto.RegisterLoginRequest;
import org.common.auth.dto.SimpleResponse;
import org.example.authservice.domain.UserAccount;
import org.example.authservice.repository.UserAccountRepository;
import org.example.authservice.service.RegisterService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RegisterServiceImpl implements RegisterService {

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    @Override
    public void register(RegisterLoginRequest registerLoginRequest) {
        if(userAccountRepository.existsByEmail(registerLoginRequest.getEmail())) {
            throw new IllegalStateException("Email already registered");
        }
        String rawPassword = registerLoginRequest.getPassword();
        String password_hash = passwordEncoder.encode(rawPassword);
        UserAccount userAccount = new UserAccount(registerLoginRequest.getEmail(),password_hash);
        userAccountRepository.save(userAccount);
    }
}
