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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PasswordResetServiceTest {

    private static final String EMAIL = "student@univ.ac.kr";
    private static final long USER_ID = 9L;

    private UserRepository userRepository;
    private PasswordResetTokenRepository tokenRepository;
    private RefreshTokenRepository refreshTokenRepository;
    private PasswordEncoder passwordEncoder;
    private ApplicationEventPublisher eventPublisher;
    private AppProperties appProperties;
    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        tokenRepository = mock(PasswordResetTokenRepository.class);
        refreshTokenRepository = mock(RefreshTokenRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        appProperties = new AppProperties();
        appProperties.setWebBaseUrl("http://localhost:5173");
        appProperties.setWebResetPasswordPath("/reset-password");
        appProperties.setPasswordResetTtl(Duration.ofMinutes(30));
        appProperties.setPasswordResetCooldown(Duration.ofSeconds(60));

        service = new PasswordResetService(
          userRepository, tokenRepository, refreshTokenRepository,
          passwordEncoder, eventPublisher, appProperties, mock(AuthRateLimiter.class));
    }

    @Nested
    @DisplayName("재설정 요청 — 계정 존재를 응답으로 드러내지 않는다")
    class Request {

        @Test
        @DisplayName("없는 이메일도 예외 없이 끝나고 메일을 보내지 않는다")
        void unknownEmailIsSilent() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

            service.request(request(EMAIL));

            verify(tokenRepository, never()).save(any());
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("카카오 전용 계정도 200 이고 메일을 보내지 않는다")
        void kakaoOnlyIsSilent() {
            User kakao = User.builder()
              .id(USER_ID).email(EMAIL).name("김카카오")
              .provider(AuthProvider.KAKAO).providerId("k-1")
              .build();
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(kakao));

            service.request(request(EMAIL));

            verify(tokenRepository, never()).save(any());
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("로컬 유저에게는 링크가 WEB_BASE_URL 로 시작하는 메일을 예약한다")
        void localUserGetsWebLink() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(localUser()));
            when(tokenRepository.findFirstByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(Optional.empty());
            when(tokenRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            service.request(request(EMAIL));

            ArgumentCaptor<PasswordResetLinkIssuedEvent> event
              = ArgumentCaptor.forClass(PasswordResetLinkIssuedEvent.class);
            verify(eventPublisher).publishEvent(event.capture());
            assertThat(event.getValue().email()).isEqualTo(EMAIL);
            assertThat(event.getValue().url()).startsWith("http://localhost:5173/reset-password?token=");
            assertThat(event.getValue().url()).doesNotContain("localhost:8080");
        }

        @Test
        @DisplayName("쿨다운 안의 재요청은 메일을 다시 보내지 않는다 (429 로 존재를 드러내지 않는다)")
        void cooldownIsSilent() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(localUser()));
            PasswordResetToken recent = PasswordResetToken.builder()
              .userId(USER_ID).tokenHash("abc").expiresAt(LocalDateTime.now().plusMinutes(30))
              .createdAt(LocalDateTime.now().minusSeconds(10))
              .build();
            when(tokenRepository.findFirstByUserIdOrderByCreatedAtDesc(USER_ID))
              .thenReturn(Optional.of(recent));

            service.request(request(EMAIL));

            verify(tokenRepository, never()).save(any());
            verify(eventPublisher, never()).publishEvent(any());
        }
    }

    @Nested
    @DisplayName("재설정 확정")
    class Confirm {

        @Test
        @DisplayName("성공하면 비밀번호를 바꾸고 전 세션 refresh 를 지운다")
        void successRevokesSessions() {
            String raw = "raw-token";
            PasswordResetToken stored = stored(raw, LocalDateTime.now().plusMinutes(10));
            when(tokenRepository.findByTokenHash(PasswordResetService.sha256(raw)))
              .thenReturn(Optional.of(stored));
            User user = localUser();
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(passwordEncoder.encode("new-password")).thenReturn("encoded-new");

            service.confirm(confirm(raw, "new-password", "new-password"));

            assertThat(user.getPassword()).isEqualTo("encoded-new");
            assertThat(stored.isUsed()).isTrue();
            verify(refreshTokenRepository).deleteByUserId(USER_ID);
        }

        @Test
        @DisplayName("확인 비밀번호가 다르면 PASSWORD_MISMATCH")
        void confirmMismatch() {
            assertThatThrownBy(() -> service.confirm(confirm("raw", "new-password", "other-password")))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.PASSWORD_MISMATCH);

            verify(tokenRepository, never()).findByTokenHash(anyString());
        }

        @Test
        @DisplayName("없는 토큰은 INVALID_TOKEN")
        void unknownToken() {
            when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.confirm(confirm("raw", "new-password", "new-password")))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.INVALID_TOKEN);
        }

        @Test
        @DisplayName("이미 쓴 토큰은 INVALID_TOKEN")
        void reusedToken() {
            PasswordResetToken stored = stored("raw", LocalDateTime.now().plusMinutes(10));
            stored.markUsed();
            when(tokenRepository.findByTokenHash(PasswordResetService.sha256("raw")))
              .thenReturn(Optional.of(stored));

            assertThatThrownBy(() -> service.confirm(confirm("raw", "new-password", "new-password")))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.INVALID_TOKEN);

            verify(refreshTokenRepository, never()).deleteByUserId(anyLong());
        }

        @Test
        @DisplayName("만료된 토큰은 TOKEN_EXPIRED")
        void expiredToken() {
            PasswordResetToken stored = stored("raw", LocalDateTime.now().minusMinutes(1));
            when(tokenRepository.findByTokenHash(PasswordResetService.sha256("raw")))
              .thenReturn(Optional.of(stored));

            assertThatThrownBy(() -> service.confirm(confirm("raw", "new-password", "new-password")))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.TOKEN_EXPIRED);
        }
    }

    private User localUser() {
        return User.builder()
          .id(USER_ID).email(EMAIL).password("old-encoded")
          .provider(AuthProvider.LOCAL).name("김학생")
          .build();
    }

    private PasswordResetToken stored(String raw, LocalDateTime expiresAt) {
        return PasswordResetToken.builder()
          .id(1L).userId(USER_ID).tokenHash(PasswordResetService.sha256(raw))
          .expiresAt(expiresAt)
          .build();
    }

    private PasswordResetRequest request(String email) {
        PasswordResetRequest request = new PasswordResetRequest();
        request.setEmail(email);
        return request;
    }

    private PasswordResetConfirmRequest confirm(String token, String next, String confirm) {
        PasswordResetConfirmRequest request = new PasswordResetConfirmRequest();
        request.setToken(token);
        request.setNewPassword(next);
        request.setNewPasswordConfirm(confirm);
        return request;
    }
}
