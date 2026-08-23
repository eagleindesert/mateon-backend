package com.example.mateon.user.controller;

import com.example.mateon.common.dto.BaseResponse;
import com.example.mateon.user.dto.MyPageResponseDTO;
import com.example.mateon.user.dto.PasswordChangeRequest;
import com.example.mateon.user.dto.UserProfileResponse;
import com.example.mateon.user.dto.UserResponse;
import com.example.mateon.user.dto.UserUpdateRequest;
import com.example.mateon.user.service.ProfileImageService;
import com.example.mateon.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "사용자", description = "내 프로필 조회·수정, 공개 프로필, 프로필 이미지")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final ProfileImageService profileImageService;

    @Operation(summary = "내 정보 조회",
      description = """
                    마이페이지 화면이 쓰는 경로다. 프로필 기본 항목에 더해 협업 온도·평가 건수·
                    참여 활동까지 한 번에 실린다 (폐기 예정인 `/mypage` 와 같은 값).

                    대상은 토큰의 주인이라 경로에 userId 를 넣지 않는다.""")
    @ApiResponse(responseCode = "200", description = "내 프로필. 협업 온도와 참여 활동까지 함께 실린다.")
    @ApiResponse(responseCode = "404",
      description = "USER_NOT_FOUND — 사용자를 찾을 수 없습니다.")
    @GetMapping("/me")
    public ResponseEntity<BaseResponse<UserResponse>> getMyProfile(Authentication authentication) {
        Long userId = Long.valueOf(authentication.getName());
        UserResponse response = userService.getMyProfile(userId);
        return ResponseEntity.ok(BaseResponse.success(response));
    }

    @Operation(summary = "내 정보 수정",
      description = """
                    보낸 필드만 바뀐다 — 생략하거나 null 로 둔 항목은 기존 값이 유지되므로
                    수정 화면에서 바꾼 것만 실어 보내도 된다.

                    이메일·학교 인증 상태·비밀번호는 여기서 바꿀 수 없다
                    (비밀번호는 `POST /api/users/password/change`).""")
    @ApiResponse(responseCode = "200", description = "수정 후의 내 프로필.")
    @ApiResponse(responseCode = "404",
      description = "USER_NOT_FOUND — 사용자를 찾을 수 없습니다.")
    @PutMapping("/me")
    public ResponseEntity<BaseResponse<UserResponse>> updateMyProfile(
      Authentication authentication,
      @Valid @RequestBody UserUpdateRequest request) {
        Long userId = Long.valueOf(authentication.getName());
        UserResponse response = userService.updateMyProfile(userId, request);
        return ResponseEntity.ok(BaseResponse.success("정보가 수정되었습니다.", response));
    }

    /**
     * 마이페이지 종합 정보 [인증 필수].
     *
     * @deprecated {@code GET /api/users/me} 가 같은 값을 모두 싣는다 (협업 온도·평가 건수·참여 활동
     * 포함). 프론트가 쓰지 않는 중복 경로이며, 새로 붙이는 화면은 {@code /me} 를 쓸 것.
     * 경로 제거는 프론트 확인 후 별건으로 진행하므로 <b>지금 응답은 그대로다</b>.
     */
    @Deprecated
    @Operation(
      deprecated = true,
      summary = "[폐기 예정] 마이페이지 종합 정보 조회",
      description = "GET /api/users/me 로 대체되었습니다. 같은 값을 모두 주므로 새 호출부는 /me 를 쓰세요.")
    @ApiResponse(responseCode = "200", description = "마이페이지 종합 정보. /me 가 같은 값을 준다.")
    @ApiResponse(responseCode = "404",
      description = "USER_NOT_FOUND — 사용자를 찾을 수 없습니다.")
    @GetMapping("/mypage")
    public ResponseEntity<BaseResponse<MyPageResponseDTO>> getMyPage(Authentication authentication) {
        Long userId = Long.valueOf(authentication.getName());
        MyPageResponseDTO response = userService.getMyPage(userId);
        return ResponseEntity.ok(BaseResponse.success(response));
    }

    /**
     * 남의 프로필 조회 [인증 필수].
     *
     * <p>
     * 추천 목록·팀 상세·역제안·DM 어디서든 응답에 담긴 userId 하나로 이 경로를 열면 된다.
     * 내려가는 값은 연락처를 배제한 공개 항목뿐이다 ({@link UserProfileResponse} 참고).
     *
     * <p>
     * 경로를 숫자로 못박아 둔 이유: {@code /me}, {@code /mypage} 같은 리터럴 경로가
     * 실수로 이 핸들러에 잡혀 Long 파싱 400 이 나는 걸 구조적으로 막는다.
     */
    @Operation(summary = "남의 프로필 조회",
      description = """
                          추천 목록·팀 상세·역제안·DM 어디서든 응답에 담긴 userId 하나로 이 경로를
                          열면 된다. 내려가는 값은 **연락처를 배제한 공개 항목뿐**이다.

                          자기 자신을 조회하면 isMe 가 true 로 온다. 매칭 의도 슬롯이나 협업 온도는
                          아직 없을 수 있고(의도 추출 전·평가 0건) 그때는 null 이다.""")
    @ApiResponse(responseCode = "200", description = "연락처를 뺀 공개 프로필. 자기 자신이면 isMe 가 true 다.")
    @ApiResponse(responseCode = "404",
      description = "USER_NOT_FOUND — 사용자를 찾을 수 없습니다.")
    @Parameter(name = "userId", description = "조회할 사용자. 숫자만 받는다(/me 같은 리터럴 경로와 겹치지 않게 하기 위함).")
    @GetMapping("/{userId:\\d+}")
    public ResponseEntity<BaseResponse<UserProfileResponse>> getUserProfile(
      Authentication authentication,
      @PathVariable Long userId) {
        Long viewerId = Long.valueOf(authentication.getName());
        UserProfileResponse response = userService.getPublicProfile(userId, viewerId);
        return ResponseEntity.ok(BaseResponse.success(response));
    }

    /**
     * 프로필 이미지 업로드 [인증 필수]. 요청은 multipart/form-data 이며 파트 이름은 {@code image}
     * (jpg/jpeg/png, 10MB 이하).
     *
     * <p>
     * <b>응답에 이미지 URL 이 없다.</b> 버킷 작업(이전 사진 삭제 → 새 사진 업로드)은 비동기로
     * 돌고, 이 응답은 "접수했다"까지만 알린다. 새 URL 은 잠시 뒤 유저 조회 3종
     * ({@code /me}, {@code /mypage}, {@code /{userId}}) 의 {@code profileImageUrl} 로 확인한다.
     *
     * <p>
     * 형식·크기가 잘못된 파일은 접수 단계에서 400/413 으로 즉시 거절되므로, 200 을 받았다면
     * 파일 자체에는 문제가 없다는 뜻이다.
     */
    @Operation(summary = "프로필 이미지 업로드",
      description = """
                          `multipart/form-data` 로 보내고 **파트 이름은 `image`** 다
                          (jpg/jpeg/png, 10MB 이하).

                          **응답에 이미지 URL 이 없다.** 버킷 작업(이전 사진 삭제 → 새 사진 업로드)이
                          비동기라 이 응답은 "접수했다"까지만 알린다. 새 URL 은 잠시 뒤 유저 조회
                          (`/me`, `/{userId}`)의 profileImageUrl 로 확인한다.

                          형식·크기가 잘못된 파일은 접수 단계에서 400/413 으로 즉시 거절되므로,
                          200 을 받았다면 파일 자체에는 문제가 없다는 뜻이다.""")
    @ApiResponse(responseCode = "200", description = "업로드를 접수했다. 새 URL 은 잠시 뒤 유저 조회로 확인한다.")
    @ApiResponse(responseCode = "400",
      description = "INVALID_IMAGE_FILE — jpg, jpeg, png 형식의 이미지 파일만 업로드할 수 있습니다.")
    @ApiResponse(responseCode = "413",
      description = "IMAGE_TOO_LARGE — 이미지는 10MB 이하만 업로드할 수 있습니다.")
    @ApiResponse(responseCode = "404",
      description = "USER_NOT_FOUND — 사용자를 찾을 수 없습니다.")
    @ApiResponse(responseCode = "507",
      description = "STORAGE_QUOTA_EXCEEDED — 저장 공간이 가득 찼습니다. 파일을 줄여도 통과하지 않는다.")
    @PostMapping(value = "/me/profile-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BaseResponse<Void>> uploadProfileImage(
      Authentication authentication,
      @RequestPart("image") MultipartFile image) {
        Long userId = Long.valueOf(authentication.getName());
        profileImageService.upload(userId, image);
        return ResponseEntity.ok(BaseResponse.success("프로필 이미지 업로드가 진행 중입니다.", null));
    }

    /**
     * 프로필 이미지 삭제 [인증 필수]. 업로드와 마찬가지로 실제 처리는 비동기다.
     *
     * <p>
     * 이미지가 없는 상태에서 불러도 성공한다(멱등) — 요청이 원한 결과가 이미 그 상태다.
     */
    @Operation(summary = "프로필 이미지 삭제",
      description = """
                          업로드와 마찬가지로 실제 처리는 비동기다.

                          이미지가 없는 상태에서 불러도 성공한다(멱등) — 요청이 원한 결과가
                          이미 그 상태이기 때문이다.""")
    @ApiResponse(responseCode = "200", description = "삭제를 접수했다. 원래 이미지가 없어도 성공한다.")
    @ApiResponse(responseCode = "404",
      description = "USER_NOT_FOUND — 사용자를 찾을 수 없습니다.")
    @DeleteMapping("/me/profile-image")
    public ResponseEntity<BaseResponse<Void>> deleteProfileImage(Authentication authentication) {
        Long userId = Long.valueOf(authentication.getName());
        profileImageService.delete(userId);
        return ResponseEntity.ok(BaseResponse.success("프로필 이미지 삭제가 요청되었습니다.", null));
    }

    @Operation(summary = "비밀번호 변경 (로그인 상태)",
      description = """
                          대상을 토큰으로 정하므로 email 을 받지 않는다. 로그인하지 않은 채
                          바꾸는 화면이라면 `POST /api/auth/password/change` 를 쓴다.

                          성공하면 저장된 refreshToken 이 폐기되므로 **다시 로그인해야 한다.**""")
    @ApiResponse(responseCode = "200", description = "비밀번호를 바꿨다. 다시 로그인해야 한다. data 는 null 이다.")
    @ApiResponse(responseCode = "400",
      description = "PASSWORD_MISMATCH — 현재 비밀번호가 틀렸거나, 새 비밀번호와 확인값이 다릅니다.")
    @ApiResponse(responseCode = "404",
      description = "USER_NOT_FOUND — 사용자를 찾을 수 없습니다.")
    @PostMapping("/password/change")
    public ResponseEntity<BaseResponse<Object>> changePassword(
      Authentication authentication,
      @Valid @RequestBody PasswordChangeRequest request) {
        Long userId = Long.valueOf(authentication.getName());
        userService.changePassword(userId, request);
        return ResponseEntity.ok(BaseResponse.success("비밀번호가 변경되었습니다. 다시 로그인해주세요."));
    }
}
