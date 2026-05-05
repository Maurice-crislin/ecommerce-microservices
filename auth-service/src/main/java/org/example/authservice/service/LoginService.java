package org.example.authservice.service;

import org.common.auth.dto.RegisterLoginRequest;
import org.common.auth.dto.TokenPair;

public interface LoginService {
    public TokenPair login(RegisterLoginRequest registerLoginRequest);
}
