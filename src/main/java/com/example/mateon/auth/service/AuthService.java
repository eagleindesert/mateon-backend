package com.example.mateon.auth.service;

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
    private final Random random = new Random();

    public void requestEmailVerification(EmailRequest request) {
        String email = request.getEmail();

        // 단국대 이메일 검증
        if (!email.endsWith("@dankook.ac.kr")) {
            throw new MateonException(ErrorCode.INVALID_EMAIL_DOMAIN);
        }

        // 인증코드 생성 (6자리)
        String code = String.format("%06d", random.nextInt(1000000));

        // 기존 인증 정보가 있으면 업데이트, 없으면 생성
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

        // 이메일 발송
        mailService.sendVerificationCode(email, code);
    }

    public void verifyEmail(EmailVerifyRequest request) {
        EmailVerification verification = emailVerificationRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new MateonException(ErrorCode.INVALID_VERIFICATION_CODE));

        if (!verification.isValid()) {
            throw new MateonException(ErrorCode.INVALID_VERIFICATION_CODE);
        }

        if (!verification.getCode().equals(request.getCode())) {
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

        // 사용자 생성
        User user = User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .provider(AuthProvider.LOCAL)
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

        // 토큰 발급
        String accessToken = jwtTokenProvider.createAccessToken(user.getId());
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getId());

        // 리프레시 토큰 저장
        saveRefreshToken(user.getId(), refreshToken);

        return TokenResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtProperties.getExpiration() / 1000)
                .build();
    }

    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new MateonException(ErrorCode.INVALID_CREDENTIALS));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new MateonException(ErrorCode.INVALID_CREDENTIALS);
        }

        String accessToken = jwtTokenProvider.createAccessToken(user.getId());
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getId());

        // 기존 리프레시 토큰 삭제 후 새로 저장
        refreshTokenRepository.deleteByUserId(user.getId());
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

    private void saveRefreshToken(Long userId, String refreshTokenValue) {
        RefreshToken refreshToken = RefreshToken.builder()
                .token(refreshTokenValue)
                .userId(userId)
                .expiresAt(LocalDateTime.now().plusSeconds(jwtProperties.getRefreshExpiration() / 1000))
                .build();

        refreshTokenRepository.save(refreshToken);
    }
}

