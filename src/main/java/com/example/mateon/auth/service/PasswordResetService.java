package com.example.mateon.auth.service;

import com.example.mateon.auth.config.AuthRateLimiter;
import com.example.mateon.auth.domain.PasswordResetToken;
import com.example.mateon.auth.dto.PasswordResetConfirmRequest;
import com.example.mateon.auth.dto.PasswordResetRequest;
import com.example.mateon.auth.repository.PasswordResetTokenRepository;
import com.example.mateon.auth.repository.RefreshTokenRepository;
import com.example.mateon.common.config.AppProperties;
import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.mail.event.PasswordResetLinkIssuedEvent;
import com.example.mateon.user.domain.AuthProvider;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 비밀번호 찾기. 메일 링크는 이 서버가 아니라 웹 React 재설정 페이지다.
 *
 * <p>
 * request 는 계정 존재 여부를 응답으로 드러내지 않는다. 없는 이메일·카카오 전용 계정도
 * 200 이고 메일만 안 보낸다.
 */
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;
    private final AppProperties appProperties;
    private final AuthRateLimiter authRateLimiter;

    @Transactional
    public void request(PasswordResetRequest request) {
        authRateLimiter.checkResetEmail(request.getEmail());

        User user = userRepository.findByEmail(request.getEmail()).orElse(null);
        if (user == null || user.getPassword() == null || user.getProvider() != AuthProvider.LOCAL) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        PasswordResetToken latest = passwordResetTokenRepository
          .findFirstByUserIdOrderByCreatedAtDesc(user.getId())
          .orElse(null);
        if (latest != null && latest.getCreatedAt() != null
          && latest.getCreatedAt().isAfter(now.minus(appProperties.getPasswordResetCooldown()))) {
            // 열거 방지를 위해 429 를 주지 않는다. 메일도 다시 보내지 않는다.
            return;
        }

        String raw = UUID.randomUUID() + UUID.randomUUID().toString();
        PasswordResetToken token = PasswordResetToken.builder()
          .userId(user.getId())
          .tokenHash(sha256(raw))
          .expiresAt(now.plus(appProperties.getPasswordResetTtl()))
          .build();
        passwordResetTokenRepository.save(token);

        eventPublisher.publishEvent(
          new PasswordResetLinkIssuedEvent(user.getEmail(), appProperties.passwordResetLink(raw)));
    }

    @Transactional
    public void confirm(PasswordResetConfirmRequest request) {
        if (!request.getNewPassword().equals(request.getNewPasswordConfirm())) {
            throw new MateonException(ErrorCode.PASSWORD_MISMATCH);
        }

        PasswordResetToken token = passwordResetTokenRepository.findByTokenHash(sha256(request.getToken()))
          .orElseThrow(ErrorCode.INVALID_TOKEN::toException);

        if (token.isUsed()) {
            throw new MateonException(ErrorCode.INVALID_TOKEN);
        }
        if (token.isExpired()) {
            throw new MateonException(ErrorCode.TOKEN_EXPIRED);
        }

        User user = userRepository.findById(token.getUserId())
          .orElseThrow(ErrorCode.INVALID_TOKEN::toException);
        if (user.getPassword() == null) {
            throw new MateonException(ErrorCode.INVALID_CREDENTIALS);
        }

        user.updatePassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        token.markUsed();
        passwordResetTokenRepository.save(token);
        refreshTokenRepository.deleteByUserId(user.getId());
    }

    static String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없습니다.", e);
        }
    }
}
