package com.example.mateon.auth.service;

import com.example.mateon.auth.client.KakaoUserInfo;
import com.example.mateon.auth.repository.EmailVerificationRepository;
import com.example.mateon.auth.repository.RefreshTokenRepository;
import com.example.mateon.support.TestJwt;
import com.example.mateon.user.domain.AuthProvider;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 카카오 로그인의 <b>신원 판정</b> 규칙을 고정한다. 이 도메인에서 가장 위험한 코드다 —
 * 판정을 한 칸만 틀려도 남의 계정으로 로그인이 된다.
 *
 * <p>규칙은 세 갈래다.
 * <ol>
 *   <li>{@code (KAKAO, providerId)} 로 찾히면 그 사람이다. 이메일은 보지도 않는다.</li>
 *   <li>못 찾았을 때만 이메일로 기존 계정과 연동을 시도하되, <b>카카오가 검증한 이메일만</b>
 *       신뢰한다. 미검증 이메일을 신뢰하면, 카카오 계정에 남의 학교 이메일을 적어 두는 것만으로
 *       그 계정을 통째로 가져갈 수 있다.</li>
 *   <li>둘 다 아니면 새 유저다. 학교 인증은 안 된 상태로 시작한다.</li>
 * </ol>
 *
 * <p>특히 2번의 "미검증이면 email 을 아예 null 로 저장한다"는 동작은 겉보기에 데이터 손실처럼
 * 보여서 "이메일이 있는데 왜 버리냐"며 되돌려지기 쉽다. 그래서 명시적으로 못박는다.
 */
class AuthServiceKakaoLoginTest {

    private static final String PROVIDER_ID = "kakao-99";
    private static final String EMAIL = "student@univ.ac.kr";

    private UserRepository userRepository;
    private RefreshTokenRepository refreshTokenRepository;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        refreshTokenRepository = mock(RefreshTokenRepository.class);

        authService = new AuthService(
                userRepository,
                mock(EmailVerificationRepository.class),
                refreshTokenRepository,
                mock(PasswordEncoder.class),
                mock(ApplicationEventPublisher.class),
                TestJwt.provider(),
                TestJwt.properties());

        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            if (user.getId() == null) {
                user.setId(100L);
            }
            return user;
        });
    }

    @Test
    @DisplayName("재방문 유저는 (KAKAO, providerId) 로만 찾는다 — 이메일 조회도, 저장도 하지 않는다")
    void returningUserIsFoundByProviderId() {
        User existing = User.builder()
                .id(7L).provider(AuthProvider.KAKAO).providerId(PROVIDER_ID)
                .email(EMAIL).name("김카카오")
                .build();
        when(userRepository.findByProviderAndProviderId(AuthProvider.KAKAO, PROVIDER_ID))
                .thenReturn(Optional.of(existing));

        authService.kakaoLogin(new KakaoUserInfo(PROVIDER_ID, EMAIL, true, "김카카오"));

        verify(userRepository, never()).findByEmail(anyString());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("카카오가 검증한 이메일이 기존 계정과 같으면 그 계정에 연동한다 (새 계정을 만들지 않는다)")
    void verifiedEmailLinksToExistingAccount() {
        User local = User.builder()
                .id(3L).provider(AuthProvider.LOCAL).email(EMAIL).name("김학생")
                .schoolEmail(EMAIL).schoolVerified(true)
                .build();
        when(userRepository.findByProviderAndProviderId(AuthProvider.KAKAO, PROVIDER_ID))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(local));

        authService.kakaoLogin(new KakaoUserInfo(PROVIDER_ID, EMAIL, true, "김카카오"));

        assertThat(local.getProvider()).isEqualTo(AuthProvider.KAKAO);
        assertThat(local.getProviderId()).isEqualTo(PROVIDER_ID);
        // 연동이지 신규 생성이 아니다 — 저장은 그 인스턴스 하나뿐이어야 한다.
        verify(userRepository).save(local);
        assertThat(local.isSchoolVerified()).isTrue();
    }

    @Test
    @DisplayName("미검증 이메일은 연동 후보로도 쓰지 않는다 — 남의 이메일을 적어 두고 계정을 가로채는 걸 막는다")
    void unverifiedEmailNeverLinks() {
        when(userRepository.findByProviderAndProviderId(AuthProvider.KAKAO, PROVIDER_ID))
                .thenReturn(Optional.empty());

        authService.kakaoLogin(new KakaoUserInfo(PROVIDER_ID, EMAIL, false, "김카카오"));

        verify(userRepository, never()).findByEmail(anyString());
    }

    @Test
    @DisplayName("미검증 이메일은 신규 유저에도 저장하지 않는다 (email = null)")
    void unverifiedEmailIsDiscardedOnSignup() {
        when(userRepository.findByProviderAndProviderId(AuthProvider.KAKAO, PROVIDER_ID))
                .thenReturn(Optional.empty());

        authService.kakaoLogin(new KakaoUserInfo(PROVIDER_ID, EMAIL, false, "김카카오"));

        User created = captureSavedUser();
        assertThat(created.getEmail())
                .as("카카오가 검증하지 않은 이메일은 신뢰할 수 없으므로 버린다")
                .isNull();
        assertThat(created.getProviderId()).isEqualTo(PROVIDER_ID);
        assertThat(created.getProvider()).isEqualTo(AuthProvider.KAKAO);
    }

    @Test
    @DisplayName("신규 카카오 유저는 학교 미인증 상태로 시작한다")
    void newKakaoUserIsNotSchoolVerified() {
        when(userRepository.findByProviderAndProviderId(AuthProvider.KAKAO, PROVIDER_ID))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        authService.kakaoLogin(new KakaoUserInfo(PROVIDER_ID, EMAIL, true, "김카카오"));

        User created = captureSavedUser();
        assertThat(created.isSchoolVerified()).isFalse();
        assertThat(created.getSchoolEmail()).isNull();
        assertThat(created.getEmail()).isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("닉네임 미동의면 이름이 '카카오사용자' 로 채워진다 — name 은 not null 컬럼이다")
    void nicknameFallback() {
        when(userRepository.findByProviderAndProviderId(AuthProvider.KAKAO, PROVIDER_ID))
                .thenReturn(Optional.empty());

        authService.kakaoLogin(new KakaoUserInfo(PROVIDER_ID, null, false, null));

        assertThat(captureSavedUser().getName()).isEqualTo("카카오사용자");
    }

    @Test
    @DisplayName("어느 경로로 들어오든 토큰은 발급된다")
    void alwaysIssuesTokens() {
        when(userRepository.findByProviderAndProviderId(AuthProvider.KAKAO, PROVIDER_ID))
                .thenReturn(Optional.empty());

        var response = authService.kakaoLogin(new KakaoUserInfo(PROVIDER_ID, null, false, "김카카오"));

        assertThat(response.getAccessToken()).isNotBlank();
        assertThat(response.getRefreshToken()).isNotBlank();
        assertThat(response.getTokenType()).isEqualTo("Bearer");
    }

    private User captureSavedUser() {
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        return captor.getValue();
    }
}
