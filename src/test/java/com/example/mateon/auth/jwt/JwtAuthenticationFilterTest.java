package com.example.mateon.auth.jwt;

import com.example.mateon.support.TestJwt;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 인증 필터가 SecurityContext 에 무엇을 넣는지 고정한다.
 *
 * <p>핵심은 <b>principal 이 Long 이 아니라 userId 문자열</b>이라는 점이다. 컨트롤러들은 전부
 * {@code Long.parseLong(authentication.getName())} 으로 되받는데, 여기서 principal 을 Long
 * 객체나 UserDetails 로 바꾸면 {@code getName()} 결과가 달라져 모든 인증 API 가 한꺼번에
 * 깨진다. 그래서 타입까지 못박는다.
 *
 * <p>두 번째는 <b>인증 실패가 곧 요청 실패가 아니라는 것</b>이다. 토큰이 없거나 잘못돼도 필터는
 * 컨텍스트를 비운 채 체인을 계속 진행시켜야 한다 — 공개 엔드포인트(GET /api/events,
 * GET /api/teams)가 만료된 토큰을 들고 온 브라우저에게도 열려 있어야 하기 때문이다.
 * 여기서 예외를 던지거나 응답을 끊으면 그 공개 API 들이 통째로 막힌다.
 */
class JwtAuthenticationFilterTest {

    private final JwtTokenProvider tokenProvider = TestJwt.provider();
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokenProvider);

    @AfterEach
    void clearContext() {
        // SecurityContextHolder 는 스레드 로컬이고 JUnit 은 스레드를 재사용한다.
        // 지우지 않으면 앞 테스트의 인증이 뒤 테스트로 새어 통과 이유가 뒤바뀐다.
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("유효한 Bearer 토큰")
    class ValidToken {

        @Test
        @DisplayName("principal 은 Long 이 아니라 userId 문자열이다 (컨트롤러가 getName() 으로 읽는다)")
        void principalIsUserIdString() throws Exception {
            doFilter(request("Bearer " + tokenProvider.createAccessToken(7L)));

            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth).isNotNull();
            assertThat(auth.getPrincipal()).isInstanceOf(String.class).isEqualTo("7");
            assertThat(auth.getName()).isEqualTo("7");
        }

        @Test
        @DisplayName("권한은 ROLE_USER 하나다")
        void grantsRoleUser() throws Exception {
            doFilter(request("Bearer " + tokenProvider.createAccessToken(7L)));

            assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                    .extracting(GrantedAuthority::getAuthority)
                    .containsExactly("ROLE_USER");
        }

        @Test
        @DisplayName("자격증명(비밀번호)은 컨텍스트에 남기지 않는다")
        void credentialsAreNotRetained() throws Exception {
            doFilter(request("Bearer " + tokenProvider.createAccessToken(7L)));

            assertThat(SecurityContextHolder.getContext().getAuthentication().getCredentials()).isNull();
        }
    }

    @Nested
    @DisplayName("토큰이 없거나 잘못된 경우 — 인증만 비우고 요청은 계속 간다")
    class NoAuthentication {

        @Test
        @DisplayName("Authorization 헤더가 없으면 인증 없이 체인을 진행한다")
        void noHeader() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            verify(chain).doFilter(request, response);
        }

        @Test
        @DisplayName("소문자 bearer 는 인식하지 않는다 (접두사 비교가 대소문자를 가린다)")
        void lowercaseBearerIsIgnored() throws Exception {
            doFilter(request("bearer " + tokenProvider.createAccessToken(7L)));

            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }

        @Test
        @DisplayName("Bearer 뒤 공백이 없으면 인식하지 않는다")
        void bearerWithoutSpaceIsIgnored() throws Exception {
            doFilter(request("Bearer" + tokenProvider.createAccessToken(7L)));

            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }

        @Test
        @DisplayName("만료·변조 토큰은 예외 없이 무인증으로 지나간다 — 공개 API 가 막히면 안 된다")
        void invalidTokenDoesNotThrow() throws Exception {
            doFilter(request("Bearer not-a-real-token"));

            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }

        @Test
        @DisplayName("Bearer 뒤가 비어 있어도 통과한다 (hasText 가 걸러낸다)")
        void emptyToken() throws Exception {
            doFilter(request("Bearer "));

            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }
    }

    @Test
    @DisplayName("어떤 경우에도 다음 필터를 반드시 호출한다")
    void alwaysCallsNextFilter() throws Exception {
        for (String header : new String[]{null, "", "Bearer bad", "Bearer " + tokenProvider.createAccessToken(1L)}) {
            SecurityContextHolder.clearContext();
            MockHttpServletRequest request = new MockHttpServletRequest();
            if (header != null) {
                request.addHeader("Authorization", header);
            }
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, new MockHttpServletResponse(), chain);

            assertThat(chain.getRequest())
                    .as("Authorization=%s 일 때 체인이 진행되어야 한다", header)
                    .isSameAs(request);
        }
    }

    private MockHttpServletRequest request(String authorizationHeader) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", authorizationHeader);
        return request;
    }

    private void doFilter(MockHttpServletRequest request) throws Exception {
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    }
}
