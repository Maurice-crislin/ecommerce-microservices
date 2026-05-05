package org.example.authservice.service;

import org.common.auth.dto.TokenPair;

public interface LogoutService {
    public void logout(String jwtToken, String RefreshToken);
}
