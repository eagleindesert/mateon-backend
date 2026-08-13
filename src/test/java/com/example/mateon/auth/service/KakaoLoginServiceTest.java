package com.example.mateon.auth.service;

import com.example.mateon.auth.client.KakaoOAuthClient;
import com.example.mateon.auth.client.KakaoUserInfo;
import com.example.mateon.auth.dto.KakaoLoginRequest;
import com.example.mateon.auth.dto.TokenResponse;
import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 카카오 로그인이 두 빈으로 나뉘어 있는 이유를 지킨다.
 *
 * <p>{@link AuthService} 는 클래스 레벨 {@code @Transactional} 이라 메서드에 들어서는 순간
 * DB 커넥션을 잡는다. 카카오 {@code user/me} 호출을 그 안에서 하면, 카카오가 느린 만큼
 * 커넥션이 묶인다 — 기본 풀이 10 이라 동시 로그인 몇 건으로 서비스 전체가 멈출 수 있다.
 * 그래서 외부 호출은 트랜잭션이 없는 이 클래스가 먼저 끝내고, DB 작업만 AuthService 에 넘긴다.
 *
 * <p>테스트로 붙잡는 건 <b>순서</b>다. 누군가 편의를 위해 이 두 줄을 AuthService 안으로
 * 합치면(그러면 자기호출이라 @Transactional 도 무시된다) 여기서 걸린다.
 */
class KakaoLoginServiceTest {

    private KakaoOAuthClient kakaoClient;
    private AuthService authService;
    private KakaoLoginService kakaoLoginService;

    @BeforeEach
    void setUp() {
        kakaoClient = mock(KakaoOAuthClient.class);
        authService = mock(AuthService.class);
        kakaoLoginService = new KakaoLoginService(kakaoClient, authService);
    }

    @Test
    @DisplayName("카카오 조회를 먼저 끝내고 그 결과로 DB 작업을 부른다 (커넥션을 붙잡지 않으려는 순서다)")
    void fetchesUserInfoBeforeTouchingDatabase() {
        KakaoUserInfo info = new KakaoUserInfo("kakao-1", "a@b.ac.kr", true, "김카카오");
        when(kakaoClient.fetchUserInfo("access-token")).thenReturn(info);
        when(authService.kakaoLogin(info)).thenReturn(TokenResponse.builder().accessToken("jwt").build());

        TokenResponse response = kakaoLoginService.login(request("access-token"));

        InOrder order = inOrder(kakaoClient, authService);
        order.verify(kakaoClient).fetchUserInfo("access-token");
        order.verify(authService).kakaoLogin(info);
        assertThat(response.getAccessToken()).isEqualTo("jwt");
    }

    @Test
    @DisplayName("카카오 조회가 실패하면 DB 쪽은 아예 부르지 않는다")
    void doesNotTouchDatabaseWhenKakaoFails() {
        when(kakaoClient.fetchUserInfo("bad-token"))
                .thenThrow(new MateonException(ErrorCode.KAKAO_AUTH_FAILED));

        assertThatThrownBy(() -> kakaoLoginService.login(request("bad-token")))
                .isInstanceOf(MateonException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.KAKAO_AUTH_FAILED);

        verify(authService, never()).kakaoLogin(any());
    }

    private KakaoLoginRequest request(String accessToken) {
        KakaoLoginRequest request = new KakaoLoginRequest();
        request.setAccessToken(accessToken);
        return request;
    }
}
