package org.example.authservice.service;

import org.common.auth.dto.RegisterLoginRequest;
import org.common.auth.dto.SimpleResponse;

public interface RegisterService {
    public void register(RegisterLoginRequest  registerLoginRequest);
}
