package com.example.mateon.auth.service;

import com.example.mateon.auth.client.KakaoOAuthClient;
import com.example.mateon.auth.client.KakaoUserInfo;
import com.example.mateon.auth.domain.EmailVerification;
import com.example.mateon.auth.domain.RefreshToken;
import com.example.mateon.auth.dto.*;
import com.example.mateon.auth.jwt.JwtTokenProvider;
import com.example.mateon.auth.repository.EmailVerificationRepository;
import com.example.mateon.auth.repository.RefreshTokenRepository;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.config.JwtProperties;
import com.example.mateon.mail.service.MailService;
import com.example.mateon.user.domain.AuthProvider;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Random;

@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {

    private final UserRepository userRepository;
    private final EmailVerificationRepository emailVerificationRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final MailService mailService;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;
    private final KakaoOAuthClient kakaoClient;
    private final Random random = new Random();

    public void requestEmailVerification(EmailRequest request) {
        String email = request.getEmail();

        // 대학교 이메일 검증 (현재는 모든 대학교 대상)
        if (!email.endsWith(".ac.kr")) {
            throw new MateonException(ErrorCode.INVALID_EMAIL_DOMAIN);
        }

        generateAndSendCode(email);
    }

    public void verifyEmail(EmailVerifyRequest request) {
        verifyCode(request.getEmail(), request.getCode());
    }

    // ===== 학교(재학생) 이메일 인증 : 로그인 후 단계 =====

    // 인증된 유저가 학교 이메일(.ac.kr) 인증코드를 요청한다.
    public void requestSchoolEmailVerification(Long userId, SchoolEmailRequest request) {
        // 유저 존재 확인 (미인증 소셜 유저 포함)
        if (!userRepository.existsById(userId)) {
            throw new MateonException(ErrorCode.USER_NOT_FOUND);
        }

        String schoolEmail = request.getSchoolEmail();

        // 대학교 이메일 검증
        if (!schoolEmail.endsWith(".ac.kr")) {
            throw new MateonException(ErrorCode.INVALID_EMAIL_DOMAIN);
        }

        // 다른 계정이 이미 인증에 사용 중인 학교 이메일인지 확인
        if (userRepository.existsBySchoolEmail(schoolEmail)) {
            throw new MateonException(ErrorCode.SCHOOL_EMAIL_ALREADY_USED);
        }

        generateAndSendCode(schoolEmail);
    }

    // 인증된 유저가 학교 이메일 인증코드를 검증하면 재학생 상태로 전환한다.
    public void verifySchoolEmail(Long userId, SchoolEmailVerifyRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new MateonException(ErrorCode.USER_NOT_FOUND));

        String schoolEmail = request.getSchoolEmail();

        // 다른 계정이 먼저 인증을 완료했을 수 있으므로 재확인
        if (userRepository.existsBySchoolEmail(schoolEmail)) {
            throw new MateonException(ErrorCode.SCHOOL_EMAIL_ALREADY_USED);
        }

        verifyCode(schoolEmail, request.getCode());

        user.verifySchool(schoolEmail);
        userRepository.save(user);
    }

    // 인증코드 6자리를 생성해 저장하고 메일로 발송한다. (기존 인증행이 있으면 갱신)
    private void generateAndSendCode(String email) {
        String code = String.format("%06d", random.nextInt(1000000));

        EmailVerification verification = emailVerificationRepository.findByEmail(email)
                .orElse(EmailVerification.builder()
                        .email(email)
                        .verified(false)
                        .build());

        verification = EmailVerification.builder()
                .id(verification.getId())
                .email(email)
                .code(code)
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .verified(false)
                .build();

        emailVerificationRepository.save(verification);

        mailService.sendVerificationCode(email, code);
    }

    // 저장된 인증코드와 대조해 검증하고, 통과하면 verified 로 마킹한다.
    private void verifyCode(String email, String code) {
        EmailVerification verification = emailVerificationRepository.findByEmail(email)
                .orElseThrow(() -> new MateonException(ErrorCode.INVALID_VERIFICATION_CODE));

        if (!verification.isValid()) {
            throw new MateonException(ErrorCode.INVALID_VERIFICATION_CODE);
        }

        if (!verification.getCode().equals(code)) {
            throw new MateonException(ErrorCode.INVALID_VERIFICATION_CODE);
        }

        verification.verify();
        emailVerificationRepository.save(verification);
    }

    public TokenResponse signup(SignupRequest request) {
        // 비밀번호 확인 일치 검증
        if (!request.getPassword().equals(request.getPasswordConfirm())) {
            throw new MateonException(ErrorCode.PASSWORD_MISMATCH);
        }

        // 이메일 중복 확인
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new MateonException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        // 이메일 인증 확인
        EmailVerification verification = emailVerificationRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new MateonException(ErrorCode.EMAIL_NOT_VERIFIED));

        if (!verification.getVerified()) {
            throw new MateonException(ErrorCode.EMAIL_NOT_VERIFIED);
        }

        // 사용자 생성 (로컬 유저는 학교 이메일로 선행 인증했으므로 재학생 상태로 확정)
        User user = User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .provider(AuthProvider.LOCAL)
                .schoolEmail(request.getEmail())
                .schoolVerified(true)
                .name(request.getName())
                .campus(request.getCampus())
                .college(request.getCollege())
                .major(request.getMajor())
                .grade(request.getGrade())
                .interestJobPrimary(request.getInterestJobPrimary())
                .interestJobSecondary(request.getInterestJobSecondary())
                .interestJobTertiary(request.getInterestJobTertiary())
                .tagline(request.getTagline())
                .build();

        userRepository.save(user);

        return issueTokens(user);
    }

    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new MateonException(ErrorCode.INVALID_CREDENTIALS));

        if (user.getPassword() == null
                || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new MateonException(ErrorCode.INVALID_CREDENTIALS);
        }

        return issueTokens(user);
    }

    // 카카오 액세스 토큰으로 로그인/회원가입한다. (신원 기준: provider=KAKAO + providerId)
    public TokenResponse kakaoLogin(KakaoLoginRequest request) {
        KakaoUserInfo info = kakaoClient.fetchUserInfo(request.getAccessToken());

        // 1) 재방문 카카오 유저: (KAKAO, providerId) 로 조회되면 그대로 로그인.
        User user = userRepository
                .findByProviderAndProviderId(AuthProvider.KAKAO, info.providerId())
                .orElse(null);

        if (user == null) {
            // 2) 연동 후보 이메일: 카카오가 검증한 이메일만 신뢰(도용 방지).
            String linkableEmail = (info.emailVerified() && info.email() != null) ? info.email() : null;

            if (linkableEmail != null) {
                user = userRepository.findByEmail(linkableEmail).orElse(null);
            }

            if (user != null) {
                // 2-a) 검증 이메일이 같은 기존 계정에 카카오를 연동.
                user.linkSocial(AuthProvider.KAKAO, info.providerId());
                userRepository.save(user);
            } else {
                // 2-b) 신규 카카오 유저 생성 (학교 미인증 상태로 시작).
                user = User.builder()
                        .provider(AuthProvider.KAKAO)
                        .providerId(info.providerId())
                        .email(linkableEmail) // 미동의/미검증이면 null
                        .name(info.nickname() != null ? info.nickname() : "카카오사용자")
                        .schoolVerified(false)
                        .build();
                userRepository.save(user);
            }
        }

        return issueTokens(user);
    }

    // 액세스/리프레시 토큰을 발급하고, 기존 리프레시 토큰을 교체 저장한다. (신규 유저면 삭제는 no-op)
    private TokenResponse issueTokens(User user) {
        String accessToken = jwtTokenProvider.createAccessToken(user.getId());
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getId());

        saveRefreshToken(user.getId(), refreshToken);

        return TokenResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtProperties.getExpiration() / 1000)
                .build();
    }

    public TokenResponse refreshToken(RefreshTokenRequest request) {
        String refreshTokenValue = request.getRefreshToken();

        if (!jwtTokenProvider.validateToken(refreshTokenValue)) {
            throw new MateonException(ErrorCode.INVALID_TOKEN);
        }

        RefreshToken refreshToken = refreshTokenRepository.findByToken(refreshTokenValue)
                .orElseThrow(() -> new MateonException(ErrorCode.TOKEN_NOT_FOUND));

        if (refreshToken.isExpired()) {
            refreshTokenRepository.delete(refreshToken);
            throw new MateonException(ErrorCode.TOKEN_EXPIRED);
        }

        Long userId = jwtTokenProvider.getUserIdFromToken(refreshTokenValue);
        String newAccessToken = jwtTokenProvider.createAccessToken(userId);

        return TokenResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(refreshTokenValue)
                .tokenType("Bearer")
                .expiresIn(jwtProperties.getExpiration() / 1000)
                .build();
    }

    public void changePassword(ChangePasswordRequest request) {
        if (!request.getNewPassword().equals(request.getNewPasswordConfirm())) {
            throw new MateonException(ErrorCode.PASSWORD_MISMATCH);
        }

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new MateonException(ErrorCode.USER_NOT_FOUND));

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            throw new MateonException(ErrorCode.PASSWORD_MISMATCH);
        }

        user.updatePassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        // 비밀번호 변경 시 리프레시 토큰 삭제 (재로그인 필요)
        refreshTokenRepository.deleteByUserId(user.getId());
    }

    public void logout(LogoutRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new MateonException(ErrorCode.USER_NOT_FOUND));
        refreshTokenRepository.deleteByUserId(user.getId());
    }

    // 리프레시 토큰을 유저당 1행으로 upsert 한다.
    //   기존 행이 있으면 토큰/만료시각만 교체(rotate)하고, 없으면 새로 만든다.
    //   (delete→insert 방식은 Hibernate flush 순서상 INSERT 가 DELETE 보다 먼저 나가
    //    같은 토큰 값 재발급 시 UNIQUE 제약과 충돌하므로 upsert 로 대체한다.)
    private void saveRefreshToken(Long userId, String refreshTokenValue) {
        LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(jwtProperties.getRefreshExpiration() / 1000);

        RefreshToken refreshToken = refreshTokenRepository.findByUserId(userId)
                .map(existing -> { existing.rotate(refreshTokenValue, expiresAt); return existing; })
                .orElseGet(() -> RefreshToken.builder()
                        .token(refreshTokenValue)
                        .userId(userId)
                        .expiresAt(expiresAt)
                        .build());

        refreshTokenRepository.save(refreshToken);
    }
}

