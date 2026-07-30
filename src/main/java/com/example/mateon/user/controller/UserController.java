package com.example.mateon.user.controller;

import com.example.mateon.common.dto.ApiResponse;
import com.example.mateon.user.dto.MyPageResponseDTO;
import com.example.mateon.user.dto.PasswordChangeRequest;
import com.example.mateon.user.dto.UserProfileResponse;
import com.example.mateon.user.dto.UserResponse;
import com.example.mateon.user.dto.UserUpdateRequest;
import com.example.mateon.user.service.ProfileImageService;
import com.example.mateon.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final ProfileImageService profileImageService;

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

    /**
     * 프로필 이미지 업로드 [인증 필수]. 요청은 multipart/form-data 이며 파트 이름은 {@code image}
     * (jpg/jpeg/png, 10MB 이하).
     *
     * <p><b>응답에 이미지 URL 이 없다.</b> 버킷 작업(이전 사진 삭제 → 새 사진 업로드)은 비동기로
     * 돌고, 이 응답은 "접수했다"까지만 알린다. 새 URL 은 잠시 뒤 유저 조회 3종
     * ({@code /me}, {@code /mypage}, {@code /{userId}}) 의 {@code profileImageUrl} 로 확인한다.
     *
     * <p>형식·크기가 잘못된 파일은 접수 단계에서 400/413 으로 즉시 거절되므로, 200 을 받았다면
     * 파일 자체에는 문제가 없다는 뜻이다.
     */
    @PostMapping(value = "/me/profile-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Void>> uploadProfileImage(
            Authentication authentication,
            @RequestPart("image") MultipartFile image) {
        Long userId = Long.valueOf(authentication.getName());
        profileImageService.upload(userId, image);
        return ResponseEntity.ok(ApiResponse.success("프로필 이미지 업로드가 진행 중입니다.", null));
    }

    /**
     * 프로필 이미지 삭제 [인증 필수]. 업로드와 마찬가지로 실제 처리는 비동기다.
     *
     * <p>이미지가 없는 상태에서 불러도 성공한다(멱등) — 요청이 원한 결과가 이미 그 상태다.
     */
    @DeleteMapping("/me/profile-image")
    public ResponseEntity<ApiResponse<Void>> deleteProfileImage(Authentication authentication) {
        Long userId = Long.valueOf(authentication.getName());
        profileImageService.delete(userId);
        return ResponseEntity.ok(ApiResponse.success("프로필 이미지 삭제가 요청되었습니다.", null));
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

