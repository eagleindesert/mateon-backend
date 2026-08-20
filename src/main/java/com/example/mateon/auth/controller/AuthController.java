package com.example.mateon.auth.controller;

import com.example.mateon.auth.dto.*;
import com.example.mateon.auth.service.AuthService;
import com.example.mateon.auth.service.KakaoLoginService;
import com.example.mateon.common.dto.BaseResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * 인증 API.
 */
@Tag(name = "인증", description = "회원가입·로그인·토큰 재발급과 이메일/학교 인증")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final KakaoLoginService kakaoLoginService;

    @Operation(summary = "회원가입용 이메일 인증코드 발송",
      description = """
                    가입 흐름의 1단계다: 여기서 코드를 받고 → `/email/verify` 로 검증해
                    verificationToken 을 얻고 → 그 토큰으로 `/signup` 을 부른다.

                    학교 이메일(`.ac.kr`)만 받는다. 코드는 6자리이고 5분 뒤 만료된다.
                    같은 주소로 60초 안에 다시 요청하면 429 로 거절한다(메일 폭탄 방지).""")
    @ApiResponse(responseCode = "400",
      description = "INVALID_EMAIL_DOMAIN — 교육기관 이메일(.ac.kr)만 사용 가능합니다.")
    @ApiResponse(responseCode = "429",
      description = "EMAIL_REQUEST_TOO_FREQUENT — 인증코드 요청은 잠시 후 다시 시도해주세요. (재요청 쿨다운 60초)")
    @SecurityRequirement(name = "")  // 비로그인 허용
    @PostMapping("/email/request")
    public ResponseEntity<BaseResponse<Object>> requestEmailVerification(@Valid @RequestBody EmailRequest request) {
        authService.requestEmailVerification(request);
        return ResponseEntity.ok(BaseResponse.success("인증코드가 발송되었습니다."));
    }

    @Operation(summary = "회원가입용 이메일 인증코드 검증",
      description = """
                    성공하면 일회용 verificationToken 을 돌려준다. 이 값을 `/signup` 의
                    verificationToken 에 그대로 넣어야 가입이 된다 — 인증을 마친 주소를 제3자가
                    선점하지 못하게 하는 장치다.

                    토큰은 발급 후 30분간 유효하므로 그 안에 가입을 마쳐야 한다.""")
    @ApiResponse(responseCode = "400",
      description = "INVALID_VERIFICATION_CODE — 인증코드가 올바르지 않거나 만료되었습니다. (발송 이력이 없는 주소도 같은 코드)")
    @SecurityRequirement(name = "")  // 비로그인 허용
    @PostMapping("/email/verify")
    public ResponseEntity<BaseResponse<EmailVerifyResponse>> verifyEmail(@Valid @RequestBody EmailVerifyRequest request) {
        String verificationToken = authService.verifyEmail(request);
        return ResponseEntity.ok(BaseResponse.success(
          "이메일 인증이 완료되었습니다.", new EmailVerifyResponse(verificationToken)));
    }

    // 로그인 후 학교(재학생) 이메일 인증코드 발송 [인증 필요]
    @Operation(summary = "학교 이메일 인증코드 발송",
      description = """
                    카카오로 가입한 유저가 재학생 인증을 하는 경로다 — **로그인한 뒤** 부른다
                    (자체 회원가입은 이미 학교 이메일로 인증하므로 필요 없다).

                    팀 모집글 작성·지원처럼 재학생만 쓸 수 있는 기능
                    (400 SCHOOL_NOT_VERIFIED)이 막혀 있다면 이 흐름을 태우면 된다.
                    코드 규칙(6자리·5분·60초 쿨다운)은 회원가입용과 같다.""")
    @ApiResponse(responseCode = "400", description = """
            INVALID_EMAIL_DOMAIN — 교육기관 이메일(.ac.kr)만 사용 가능합니다.
            SCHOOL_EMAIL_ALREADY_USED — 이미 다른 계정에서 사용 중인 학교 이메일입니다.""")
    @ApiResponse(responseCode = "404",
      description = "USER_NOT_FOUND — 사용자를 찾을 수 없습니다.")
    @ApiResponse(responseCode = "429",
      description = "EMAIL_REQUEST_TOO_FREQUENT — 인증코드 요청은 잠시 후 다시 시도해주세요.")
    @PostMapping("/school/email/request")
    public ResponseEntity<BaseResponse<Object>> requestSchoolEmailVerification(
      @Valid @RequestBody SchoolEmailRequest request,
      Authentication authentication) {
        Long userId = Long.valueOf(authentication.getName());
        authService.requestSchoolEmailVerification(userId, request);
        return ResponseEntity.ok(BaseResponse.success("학교 이메일로 인증코드가 발송되었습니다."));
    }

    // 로그인 후 학교(재학생) 이메일 인증코드 검증 → 재학생 확정 [인증 필요]
    @Operation(summary = "학교 이메일 인증코드 검증 (재학생 확정)",
      description = """
                    통과하면 그 자리에서 재학생 상태가 되고, 학교 인증이 필요한 기능이 열린다.
                    `/school/email/request` 에 보낸 주소를 그대로 다시 보내야 한다.""")
    @ApiResponse(responseCode = "400", description = """
            INVALID_VERIFICATION_CODE — 인증코드가 올바르지 않거나 만료되었습니다.
            SCHOOL_EMAIL_ALREADY_USED — 코드를 받는 사이 다른 계정이 먼저 인증을 마쳤습니다.""")
    @ApiResponse(responseCode = "404",
      description = "USER_NOT_FOUND — 사용자를 찾을 수 없습니다.")
    @PostMapping("/school/email/verify")
    public ResponseEntity<BaseResponse<Object>> verifySchoolEmail(
      @Valid @RequestBody SchoolEmailVerifyRequest request,
      Authentication authentication) {
        Long userId = Long.valueOf(authentication.getName());
        authService.verifySchoolEmail(userId, request);
        return ResponseEntity.ok(BaseResponse.success("학교 인증이 완료되었습니다."));
    }

    @Operation(summary = "회원가입",
      description = """
                    `/email/verify` 로 받은 verificationToken 이 필요하다. 성공하면 로그인까지
                    끝난 상태로 accessToken·refreshToken 이 바로 내려간다 — 이어서 `/login` 을
                    부를 필요가 없다.

                    가입 이메일로 학교 인증까지 마친 것으로 처리되므로 재학생 전용 기능을
                    곧바로 쓸 수 있다.

                    새 자원이 생기지만 응답은 201 이 아니라 200 이다(기존 클라이언트 호환).""")
    @ApiResponse(responseCode = "400", description = """
            PASSWORD_MISMATCH — password 와 passwordConfirm 이 다릅니다.
            EMAIL_ALREADY_EXISTS — 이미 사용 중인 이메일입니다.
            EMAIL_NOT_VERIFIED — 이메일 인증이 완료되지 않았습니다.
            INVALID_VERIFICATION_TOKEN — 티켓이 틀렸거나 30분이 지났습니다. 인증부터 다시 합니다.""")
    @SecurityRequirement(name = "")  // 비로그인 허용
    @PostMapping("/signup")
    public ResponseEntity<BaseResponse<TokenResponse>> signup(@Valid @RequestBody SignupRequest request) {
        TokenResponse response = authService.signup(request);
        return ResponseEntity.ok(BaseResponse.success("회원가입이 완료되었습니다.", response));
    }

    @Operation(summary = "로그인",
      description = """
                    자체 가입(LOCAL) 계정용이다. 카카오로 가입한 계정은 비밀번호가 없어
                    여기서는 INVALID_CREDENTIALS 가 되므로 `/social/kakao` 를 쓴다.

                    응답의 accessToken 을 우측 상단 Authorize 에 넣으면 이 문서에서 다른
                    엔드포인트를 그대로 호출해 볼 수 있다.""")
    @ApiResponse(responseCode = "400",
      description = "INVALID_CREDENTIALS — 이메일 또는 비밀번호가 올바르지 않습니다. (없는 계정도 같은 코드)")
    @SecurityRequirement(name = "")  // 비로그인 허용
    @PostMapping("/login")
    public ResponseEntity<BaseResponse<TokenResponse>> login(@Valid @RequestBody LoginRequest request) {
        TokenResponse response = authService.login(request);
        return ResponseEntity.ok(BaseResponse.success("로그인 성공", response));
    }

    // 카카오 소셜 로그인/회원가입 [인증 불필요]. RN 이 카카오 SDK 로 받은 access token 을 넘긴다.
    @Operation(summary = "카카오 로그인/회원가입",
      description = """
                    앱이 카카오 SDK 로 받은 **카카오 access token** 을 그대로 넘긴다
                    (인가코드가 아니다). 서버가 카카오에 사용자 정보를 물어보고, 처음이면
                    가입까지 한 뒤 우리 토큰을 발급한다 — 로그인과 회원가입이 한 경로다.

                    이렇게 만든 계정은 학교 인증이 안 된 상태라, 재학생 전용 기능을 쓰려면
                    `/school/email/request` → `/school/email/verify` 를 거쳐야 한다.""")
    @ApiResponse(responseCode = "400",
      description = "KAKAO_AUTH_FAILED — 카카오 인증에 실패했습니다. (토큰 만료·위조)")
    @SecurityRequirement(name = "")  // 비로그인 허용
    @PostMapping("/social/kakao")
    public ResponseEntity<BaseResponse<TokenResponse>> kakaoLogin(@Valid @RequestBody KakaoLoginRequest request) {
        TokenResponse response = kakaoLoginService.login(request);
        return ResponseEntity.ok(BaseResponse.success("카카오 로그인 성공", response));
    }

    @Operation(summary = "accessToken 재발급",
      description = """
                    accessToken 이 만료돼 API 가 403 을 내기 시작하면 refreshToken 으로 새 것을 받는다.

                    갱신되는 건 accessToken 뿐이고 응답의 refreshToken 은 보낸 값 그대로다 —
                    저장해 둔 refreshToken 을 바꿀 필요가 없다.""")
    @ApiResponse(responseCode = "400", description = """
            INVALID_TOKEN — 유효하지 않은 토큰입니다.
            TOKEN_NOT_FOUND — 리프레시 토큰을 찾을 수 없습니다. (로그아웃·비밀번호 변경으로 폐기됨)
            TOKEN_EXPIRED — 토큰이 만료되었습니다. 다시 로그인해야 합니다.""")
    @SecurityRequirement(name = "")  // 만료된 accessToken 으로도 불러야 하므로 비로그인 허용
    @PostMapping("/token/refresh")
    public ResponseEntity<BaseResponse<TokenResponse>> refreshToken(@Valid @RequestBody RefreshTokenRequest request) {
        TokenResponse response = authService.refreshToken(request);
        return ResponseEntity.ok(BaseResponse.success("토큰이 갱신되었습니다.", response));
    }

    @Operation(summary = "비밀번호 변경 (비로그인)",
      description = """
                    email 과 현재 비밀번호로 본인을 확인하므로 토큰 없이 부를 수 있다.

                    로그인 상태에서 바꾸는 화면이라면 `POST /api/users/password/change` 를 쓴다
                    (그쪽은 토큰으로 대상을 정해 email 을 받지 않는다).

                    성공하면 저장된 refreshToken 이 폐기되므로 **다시 로그인해야 한다.**""")
    @ApiResponse(responseCode = "400",
      description = "PASSWORD_MISMATCH — 현재 비밀번호가 틀렸거나, newPassword 와 newPasswordConfirm 이 다릅니다.")
    @ApiResponse(responseCode = "404",
      description = "USER_NOT_FOUND — 사용자를 찾을 수 없습니다.")
    @SecurityRequirement(name = "")  // 비로그인 허용
    @PostMapping("/password/change")
    public ResponseEntity<BaseResponse<Object>> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(request);
        return ResponseEntity.ok(BaseResponse.success("비밀번호가 변경되었습니다. 다시 로그인해주세요."));
    }

    @Operation(summary = "로그아웃",
      description = """
                    서버에 저장된 refreshToken 을 지운다. 이미 발급된 accessToken 은 만료될
                    때까지 유효하므로 **앱에서도 저장한 토큰을 지워야** 로그아웃이 끝난다.

                    토큰이 아니라 본문의 email 로 대상을 정한다.""")
    @ApiResponse(responseCode = "404",
      description = "USER_NOT_FOUND — 사용자를 찾을 수 없습니다.")
    @SecurityRequirement(name = "")  // 비로그인 허용
    @PostMapping("/logout")
    public ResponseEntity<BaseResponse<Object>> logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request);
        return ResponseEntity.ok(BaseResponse.success("로그아웃되었습니다."));
    }
}
