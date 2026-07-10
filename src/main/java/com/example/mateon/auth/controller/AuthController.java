package com.example.mateon.auth.controller;

import com.example.mateon.auth.dto.*;
import com.example.mateon.auth.service.AuthService;
import com.example.mateon.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/email/request")
    public ResponseEntity<ApiResponse<Object>> requestEmailVerification(@Valid @RequestBody EmailRequest request) {
        authService.requestEmailVerification(request);
        return ResponseEntity.ok(ApiResponse.success("인증코드가 발송되었습니다."));
    }

    @PostMapping("/email/verify")
    public ResponseEntity<ApiResponse<Object>> verifyEmail(@Valid @RequestBody EmailVerifyRequest request) {
        authService.verifyEmail(request);
        return ResponseEntity.ok(ApiResponse.success("이메일 인증이 완료되었습니다."));
    }

    // 로그인 후 학교(재학생) 이메일 인증코드 발송 [인증 필요]
    @PostMapping("/school/email/request")
    public ResponseEntity<ApiResponse<Object>> requestSchoolEmailVerification(
            @Valid @RequestBody SchoolEmailRequest request,
            Authentication authentication) {
        Long userId = Long.valueOf(authentication.getName());
        authService.requestSchoolEmailVerification(userId, request);
        return ResponseEntity.ok(ApiResponse.success("학교 이메일로 인증코드가 발송되었습니다."));
    }

    // 로그인 후 학교(재학생) 이메일 인증코드 검증 → 재학생 확정 [인증 필요]
    @PostMapping("/school/email/verify")
    public ResponseEntity<ApiResponse<Object>> verifySchoolEmail(
            @Valid @RequestBody SchoolEmailVerifyRequest request,
            Authentication authentication) {
        Long userId = Long.valueOf(authentication.getName());
        authService.verifySchoolEmail(userId, request);
        return ResponseEntity.ok(ApiResponse.success("학교 인증이 완료되었습니다."));
    }

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<TokenResponse>> signup(@Valid @RequestBody SignupRequest request) {
        TokenResponse response = authService.signup(request);
        return ResponseEntity.ok(ApiResponse.success("회원가입이 완료되었습니다.", response));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<TokenResponse>> login(@Valid @RequestBody LoginRequest request) {
        TokenResponse response = authService.login(request);
        return ResponseEntity.ok(ApiResponse.success("로그인 성공", response));
    }

    @PostMapping("/token/refresh")
    public ResponseEntity<ApiResponse<TokenResponse>> refreshToken(@Valid @RequestBody RefreshTokenRequest request) {
        TokenResponse response = authService.refreshToken(request);
        return ResponseEntity.ok(ApiResponse.success("토큰이 갱신되었습니다.", response));
    }

    @PostMapping("/password/change")
    public ResponseEntity<ApiResponse<Object>> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(request);
        return ResponseEntity.ok(ApiResponse.success("비밀번호가 변경되었습니다. 다시 로그인해주세요."));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Object>> logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request);
        return ResponseEntity.ok(ApiResponse.success("로그아웃되었습니다."));
    }
}

