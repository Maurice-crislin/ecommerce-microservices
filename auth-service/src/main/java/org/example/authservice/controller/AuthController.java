package org.example.authservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.common.auth.dto.RegisterLoginRequest;
import org.common.auth.dto.SimpleResponse;
import org.common.auth.dto.TokenPair;
import org.example.authservice.service.LoginService;
import org.example.authservice.service.LogoutService;
import org.example.authservice.service.RegisterService;
import org.example.authservice.service.TokenService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller 只处理成功路径 - 所有方法都返回 ResponseEntity.ok(...)，即 HTTP 200
 * 错误通过异常抛出 - 服务层抛出异常
 * 由 @ControllerAdvice 统一处理异常 - 将异常转换为合适的 HTTP 状态码和错误响应体
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
    private final RegisterService registerService;
    private final LoginService loginService;
    private final LogoutService logoutService;
    private final TokenService tokenService;

    @PostMapping("/register")
    public ResponseEntity<SimpleResponse<Void>> register(@RequestBody @Valid RegisterLoginRequest request) {
        registerService.register(request);
        return ResponseEntity.ok(SimpleResponse.successMessage("Register success"));
    }

    @PostMapping("/login")
    public ResponseEntity<SimpleResponse<TokenPair>> login(@RequestBody @Valid RegisterLoginRequest request) {
        TokenPair tokenPair = loginService.login(request);
        return ResponseEntity.ok(SimpleResponse.success(tokenPair));
    }

    @PostMapping("/logout")
    public ResponseEntity<SimpleResponse<Void>> logout(@RequestBody @Valid TokenPair pair) {
        logoutService.logout(pair.getAccessToken(), pair.getRefreshToken());
        return ResponseEntity.ok(SimpleResponse.successMessage("logout successfully"));
    }

    @PostMapping("/refresh")
    public ResponseEntity<SimpleResponse<TokenPair>> refresh(@RequestBody String refreshToken) {
        TokenPair tokenPair = tokenService.regenerateBothToken(refreshToken);
        return ResponseEntity.ok(SimpleResponse.success(tokenPair));
    }
}
