package com.example.mateon.auth.service;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.user.domain.AuthProvider;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * username 파라미터가 이메일이 아니라 <b>userId</b> 라는 사실을 고정한다.
 *
 * <p>
 * 이름이 {@code loadUserByUsername} 이라 이메일을 넘기게 되어 있을 것 같지만, 이 프로젝트의
 * JWT subject 는 userId 다. 여기서 이메일 조회로 "고치면" 컴파일은 되고 로그인 화면도 그대로인데
 * 인증만 조용히 실패한다.
 *
 * <p>
 * 또 하나는 소셜 유저 처리다. 카카오 유저는 {@code password} 가 null 인데, Spring Security 의
 * {@code User.builder().password(null)} 은 IllegalArgumentException 을 던진다. 빈 문자열로
 * 바꿔치는 한 줄이 없으면 소셜 유저는 이 경로를 통과할 수 없다.
 */
class CustomUserDetailsServiceTest {

    private UserRepository userRepository;
    private CustomUserDetailsService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        service = new CustomUserDetailsService(userRepository);
    }

    @Test
    @DisplayName("username 으로 넘어온 값은 userId 로 조회된다 (이메일이 아니다)")
    void looksUpById() {
        when(userRepository.findById(42L)).thenReturn(Optional.of(
          User.builder().id(42L).email("a@b.ac.kr").password("encoded").name("김학생").build()));

        UserDetails details = service.loadUserByUsername("42");

        assertThat(details.getUsername()).isEqualTo("42");
        assertThat(details.getPassword()).isEqualTo("encoded");
        assertThat(details.getAuthorities()).extracting(GrantedAuthority::getAuthority)
          .containsExactly("ROLE_USER");
    }

    @Test
    @DisplayName("비밀번호 없는 소셜 유저는 빈 문자열로 대체된다 — null 이면 빌더가 예외를 던진다")
    void socialUserGetsEmptyPassword() {
        when(userRepository.findById(42L)).thenReturn(Optional.of(
          User.builder().id(42L).provider(AuthProvider.KAKAO).providerId("k-1").name("김카카오").build()));

        assertThat(service.loadUserByUsername("42").getPassword()).isEmpty();
    }

    @Test
    @DisplayName("없는 유저면 USER_NOT_FOUND")
    void unknownUser() {
        when(userRepository.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("42"))
          .isInstanceOf(MateonException.class)
          .extracting("errorCode").isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    @Test
    @DisplayName("숫자가 아닌 값이 오면 NumberFormatException 이 그대로 난다 (호출부가 보장하는 전제)")
    void nonNumericUsername() {
        assertThatThrownBy(() -> service.loadUserByUsername("a@b.ac.kr"))
          .isInstanceOf(NumberFormatException.class);
    }
}
