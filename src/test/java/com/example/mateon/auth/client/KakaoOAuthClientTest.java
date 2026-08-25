package com.example.mateon.auth.client;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 카카오 {@code /v2/user/me} 호출의 요청 형태와 응답 해석을 고정한다.
 *
 * <p>
 * 요청 쪽에서 못박는 건 <b>POST + Bearer + form 콘텐츠 타입</b> 세 가지다. 카카오는 이 조합을
 * 벗어나면 거절하는데, 로컬에서는 재현되지 않고 실제 로그인 시점에만 드러난다.
 *
 * <p>
 * 응답 쪽 핵심은 <b>어떤 실패든 {@code KAKAO_AUTH_FAILED} 하나로 수렴</b>한다는 것이다.
 * 여기서 {@code RestClientResponseException} 이 그대로 새어 나가면 전역 핸들러의 catch-all 로
 * 떨어져 500 "서버 오류" 가 되고, 프론트는 "토큰이 만료됐으니 다시 로그인" 안내를 띄울 수 없다.
 *
 * <p>
 * 또한 카카오는 동의 항목에 따라 {@code kakao_account} 자체를 통째로 빼고 준다.
 * 그 경우에도 NPE 없이 null 로 채워져야 한다 — 신규 가입 경로가 여기에 달려 있다.
 */
class KakaoOAuthClientTest {

    private static final String USER_ME_URL = "https://kapi.kakao.com/v2/user/me";

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private KakaoOAuthClient client;

    @BeforeEach
    void setUp() {
        // MockRestServiceServer 는 특정 RestTemplate 인스턴스에 묶인다.
        // 스프링 컨텍스트를 띄우지 않고 여기서 만든 것을 그대로 클라이언트에 넣는다.
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new KakaoOAuthClient(restTemplate);
    }

    @Nested
    @DisplayName("요청 형태")
    class RequestShape {

        @Test
        @DisplayName("POST + Bearer 토큰 + form 콘텐츠 타입으로 부른다 (카카오가 요구하는 조합)")
        void sendsCanonicalRequest() {
            server.expect(requestTo(USER_ME_URL))
              .andExpect(method(HttpMethod.POST))
              .andExpect(header("Authorization", "Bearer access-token"))
              .andExpect(header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE))
              .andRespond(withSuccess("{\"id\":123}", MediaType.APPLICATION_JSON));

            client.fetchUserInfo("access-token");

            server.verify();
        }
    }

    @Nested
    @DisplayName("응답 해석")
    class ResponseParsing {

        @Test
        @DisplayName("숫자 회원번호는 문자열 providerId 로 바뀐다 (DB 컬럼이 varchar 다)")
        void numericIdBecomesString() {
            respondWith("""
                    {"id": 1234567890,
                     "kakao_account": {"email":"a@b.ac.kr","is_email_verified":true,
                                       "profile":{"nickname":"김카카오"}}}
                    """);

            KakaoUserInfo info = client.fetchUserInfo("t");

            assertThat(info.providerId()).isEqualTo("1234567890");
            assertThat(info.email()).isEqualTo("a@b.ac.kr");
            assertThat(info.emailVerified()).isTrue();
            assertThat(info.nickname()).isEqualTo("김카카오");
        }

        @Test
        @DisplayName("kakao_account 가 통째로 없어도 NPE 없이 null 로 채운다 (동의 안 한 유저)")
        void missingKakaoAccount() {
            respondWith("{\"id\": 42}");

            KakaoUserInfo info = client.fetchUserInfo("t");

            assertThat(info.providerId()).isEqualTo("42");
            assertThat(info.email()).isNull();
            assertThat(info.emailVerified()).isFalse();
            assertThat(info.nickname()).isNull();
        }

        @Test
        @DisplayName("is_email_verified 가 없으면 false 다 — 없는 값을 '검증됨'으로 보면 계정 탈취가 열린다")
        void absentVerifiedFlagIsFalse() {
            respondWith("{\"id\":42,\"kakao_account\":{\"email\":\"a@b.ac.kr\"}}");

            KakaoUserInfo info = client.fetchUserInfo("t");

            assertThat(info.email()).isEqualTo("a@b.ac.kr");
            assertThat(info.emailVerified()).isFalse();
        }

        @Test
        @DisplayName("profile 은 있는데 nickname 이 없으면 null 이다")
        void profileWithoutNickname() {
            respondWith("{\"id\":42,\"kakao_account\":{\"profile\":{}}}");

            assertThat(client.fetchUserInfo("t").nickname()).isNull();
        }
    }

    @Nested
    @DisplayName("실패는 전부 KAKAO_AUTH_FAILED 로 수렴한다")
    class Failures {

        @Test
        @DisplayName("id 가 없는 응답 (계약 위반)")
        void missingId() {
            respondWith("{\"kakao_account\":{}}");

            assertKakaoAuthFailed();
        }

        @Test
        @DisplayName("401 — 토큰 만료·위조. RestClientResponseException 이 새어 500 이 되면 안 된다")
        void unauthorized() {
            server.expect(requestTo(USER_ME_URL))
              .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"msg\":\"this access token does not exist\",\"code\":-401}"));

            assertKakaoAuthFailed();
        }

        @Test
        @DisplayName("카카오 5xx")
        void serverError() {
            server.expect(requestTo(USER_ME_URL))
              .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

            assertKakaoAuthFailed();
        }

        @Test
        @DisplayName("네트워크 연결 실패")
        void connectionFailure() {
            server.expect(requestTo(USER_ME_URL))
              .andRespond(withException(new IOException("connect timed out")));

            assertKakaoAuthFailed();
        }

        private void assertKakaoAuthFailed() {
            assertThatThrownBy(() -> client.fetchUserInfo("t"))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.KAKAO_AUTH_FAILED);
        }
    }

    private void respondWith(String json) {
        server.expect(requestTo(USER_ME_URL))
          .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }
}
