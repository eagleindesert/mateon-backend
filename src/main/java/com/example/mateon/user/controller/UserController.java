package com.example.mateon.user.controller;

import com.example.mateon.common.dto.ApiResponse;
import com.example.mateon.user.dto.MyPageResponseDTO;
import com.example.mateon.user.dto.PasswordChangeRequest;
import com.example.mateon.user.dto.UserResponse;
import com.example.mateon.user.dto.UserUpdateRequest;
import com.example.mateon.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> getMyProfile(Authentication authentication) {
        Long userId = Long.valueOf(authentication.getName());
        UserResponse response = userService.getMyProfile(userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PutMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> updateMyProfile(
            Authentication authentication,
            @Valid @RequestBody UserUpdateRequest request) {
        Long userId = Long.valueOf(authentication.getName());
        UserResponse response = userService.updateMyProfile(userId, request);
        return ResponseEntity.ok(ApiResponse.success("정보가 수정되었습니다.", response));
    }
    @GetMapping("/mypage")
    public ResponseEntity<ApiResponse<MyPageResponseDTO>> getMyPage(Authentication authentication) {
        Long userId = Long.valueOf(authentication.getName());
        MyPageResponseDTO response = userService.getMyPage(userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/password/change")
    public ResponseEntity<ApiResponse<Object>> changePassword(
            Authentication authentication,
            @Valid @RequestBody PasswordChangeRequest request) {
        Long userId = Long.valueOf(authentication.getName());
        userService.changePassword(userId, request);
        return ResponseEntity.ok(ApiResponse.success("비밀번호가 변경되었습니다. 다시 로그인해주세요."));
    }
}

