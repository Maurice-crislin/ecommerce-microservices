package org.example.authservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.common.auth.dto.RegisterLoginRequest;
import org.common.auth.dto.TokenPair;
import org.example.authservice.domain.UserAccount;
import org.example.authservice.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional  // 每个测试自动回滚，不污染数据库
class AuthControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void shouldRegisterUserSuccessfully() throws Exception {
        RegisterLoginRequest request = new RegisterLoginRequest("newuser@example.com", "password123");

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Register success"));

        assertTrue(userAccountRepository.existsByEmail("newuser@example.com"));
    }

    @Test
    void shouldReturnErrorWhenRegisteringDuplicateEmail() throws Exception {
        UserAccount existingUser = userAccountRepository.save(
                new UserAccount("duplicate_" + System.currentTimeMillis() + "@example.com",
                        passwordEncoder.encode("password123")));

        RegisterLoginRequest request = new RegisterLoginRequest(existingUser.getEmail(), "password123");

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Email already registered"));
    }

    @Test
    void shouldLoginSuccessfully() throws Exception {
        String uniqueEmail = "login_" + System.currentTimeMillis() + "@example.com";
        userAccountRepository.save(new UserAccount(uniqueEmail,
                passwordEncoder.encode("password123")));

        RegisterLoginRequest request = new RegisterLoginRequest(uniqueEmail, "password123");

        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertTrue(responseBody.contains("accessToken"));
        assertTrue(responseBody.contains("refreshToken"));
    }

    @Test
    void shouldReturnErrorWhenLoginWithInvalidEmail() throws Exception {
        RegisterLoginRequest request = new RegisterLoginRequest(
                "nonexistent_" + System.currentTimeMillis() + "@example.com", "password123");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Invalid email"));
    }

    @Test
    void shouldReturnErrorWhenLoginWithWrongPassword() throws Exception {
        String uniqueEmail = "wrongpw_" + System.currentTimeMillis() + "@example.com";
        userAccountRepository.save(new UserAccount(uniqueEmail,
                passwordEncoder.encode("correctPassword")));

        RegisterLoginRequest request = new RegisterLoginRequest(uniqueEmail, "wrongPassword");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Invalid password"));
    }

    @Test
    void shouldRefreshTokenSuccessfully() throws Exception {
        String uniqueEmail = "refresh_" + System.currentTimeMillis() + "@example.com";
        RegisterLoginRequest request = new RegisterLoginRequest(uniqueEmail, "password123");

        mockMvc.perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        String refreshToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .get("data").get("refreshToken").asText();

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(refreshToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty());
    }

    @Test
    void shouldReturnErrorWhenRefreshWithInvalidToken() throws Exception {
        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("invalid-refresh-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("not valid refresh token"));
    }

    @Test
    void shouldLogoutSuccessfully() throws Exception {
        String uniqueEmail = "logout_" + System.currentTimeMillis() + "@example.com";
        RegisterLoginRequest request = new RegisterLoginRequest(uniqueEmail, "password123");

        mockMvc.perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        String loginResponse = loginResult.getResponse().getContentAsString();
        String accessToken = objectMapper.readTree(loginResponse)
                .get("data").get("accessToken").asText();
        String refreshToken = objectMapper.readTree(loginResponse)
                .get("data").get("refreshToken").asText();

        TokenPair tokenPair = new TokenPair(accessToken, refreshToken);

        mockMvc.perform(post("/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tokenPair)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void shouldReturnErrorWhenLogoutWithInvalidAccessToken() throws Exception {
        TokenPair tokenPair = new TokenPair("invalid-access-token", "some-refresh-token");

        mockMvc.perform(post("/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tokenPair)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Invalid access token"));
    }

    @Test
    void shouldCompleteEndToEndFlow() throws Exception {
        String uniqueEmail = "e2e_" + System.currentTimeMillis() + "@example.com";
        RegisterLoginRequest request = new RegisterLoginRequest(uniqueEmail, "securePass123");

        // 1. Register
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        // 2. Login
        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        String loginResponse = loginResult.getResponse().getContentAsString();
        String refreshToken = objectMapper.readTree(loginResponse)
                .get("data").get("refreshToken").asText();

        assertNotNull(refreshToken);

        // 3. Refresh
        MvcResult refreshResult = mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(refreshToken))
                .andExpect(status().isOk())
                .andReturn();

        String refreshResponse = refreshResult.getResponse().getContentAsString();
        String newAccessToken = objectMapper.readTree(refreshResponse)
                .get("data").get("accessToken").asText();
        String newRefreshToken = objectMapper.readTree(refreshResponse)
                .get("data").get("refreshToken").asText();

        assertNotNull(newAccessToken);
        assertNotNull(newRefreshToken);

        // 4. Old refresh token should no longer work
        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(refreshToken))
                .andExpect(status().isBadRequest());

        // 5. Logout with new tokens
        TokenPair tokenPair = new TokenPair(newAccessToken, newRefreshToken);
        mockMvc.perform(post("/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tokenPair)))
                .andExpect(status().isOk());
    }
}