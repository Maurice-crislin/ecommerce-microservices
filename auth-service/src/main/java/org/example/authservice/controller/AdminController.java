package org.example.authservice.controller;

import lombok.RequiredArgsConstructor;
import org.common.auth.dto.SimpleResponse;
import org.common.auth.enums.UserStatus;
import org.example.authservice.service.UserStatusService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {
    private final UserStatusService userStatusService;

    @PostMapping("/ban/{userId}")
    public ResponseEntity<SimpleResponse<Void>> banUser(@PathVariable Long userId){
        userStatusService.banUser(userId);
        return ResponseEntity.ok(SimpleResponse.successMessage("user " + userId + " has been banned"));
    }

    @PostMapping("/unban/{userId}")
    public ResponseEntity<SimpleResponse<Void>> unBanUser(@PathVariable Long userId){
        userStatusService.unBanUser(userId);
        return ResponseEntity.ok(SimpleResponse.successMessage("user " + userId + " has been unbanned"));
    }
}
