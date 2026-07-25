package com.example.mateon.user.controller;

import com.example.mateon.common.dto.ApiResponse;
import com.example.mateon.user.dto.MyPageResponseDTO;
import com.example.mateon.user.dto.PasswordChangeRequest;
import com.example.mateon.user.dto.UserProfileResponse;
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

    /**
     * 남의 프로필 조회 [인증 필수].
     *
     * <p>추천 목록·팀 상세·역제안·DM 어디서든 응답에 담긴 userId 하나로 이 경로를 열면 된다.
     * 내려가는 값은 연락처를 배제한 공개 항목뿐이다 ({@link UserProfileResponse} 참고).
     *
     * <p>경로를 숫자로 못박아 둔 이유: {@code /me}, {@code /mypage} 같은 리터럴 경로가
     * 실수로 이 핸들러에 잡혀 Long 파싱 400 이 나는 걸 구조적으로 막는다.
     */
    @GetMapping("/{userId:\\d+}")
    public ResponseEntity<ApiResponse<UserProfileResponse>> getUserProfile(
            Authentication authentication,
            @PathVariable Long userId) {
        Long viewerId = Long.valueOf(authentication.getName());
        UserProfileResponse response = userService.getPublicProfile(userId, viewerId);
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

