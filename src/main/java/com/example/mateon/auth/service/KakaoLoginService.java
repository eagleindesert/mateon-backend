package com.example.mateon.auth.service;

import com.example.mateon.auth.client.KakaoOAuthClient;
import com.example.mateon.auth.client.KakaoUserInfo;
import com.example.mateon.auth.config.KakaoProperties;
import com.example.mateon.auth.dto.KakaoCodeLoginRequest;
import com.example.mateon.auth.dto.KakaoLoginRequest;
import com.example.mateon.auth.dto.TokenResponse;
import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 카카오 로그인 오케스트레이터.
 *
 * <p>
 * 클래스 레벨 @Transactional 이 없는 게 핵심이다 (MatchingIntentService 와 같은 원칙).
 * 카카오 user/me · 토큰 교환은 외부 네트워크에 달려 있어 느려질 수 있는데, 트랜잭션 안에서 하면
 * 기다리는 내내 DB 커넥션을 붙잡는다. 커넥션은 @Transactional 메서드 진입 시점(TX begin)에
 * 이미 잡히므로 "쿼리를 아직 안 했으니 괜찮다"가 통하지 않는다.
 *
 * <p>
 * 빈이 나뉜 것도 필수다 — 같은 빈 안에서 호출하면 프록시를 타지 않아 @Transactional 이
 * 무시된다.
 */
@Service
@RequiredArgsConstructor
public class KakaoLoginService {

    private final KakaoOAuthClient kakaoClient;
    private final AuthService authService;
    private final KakaoProperties kakaoProperties;

    public TokenResponse login(KakaoLoginRequest request) {
        // ① [TX 밖] 카카오에 토큰을 검증받고 사용자 정보를 받아온다.
        KakaoUserInfo info = kakaoClient.fetchUserInfo(request.getAccessToken());

        // ② [TX] 조회/연동/가입 + 토큰 발급. 여기서만 DB 커넥션을 쓴다.
        return authService.kakaoLogin(info);
    }

    /**
     * 웹 인가코드 로그인. redirectUri 가 허용 목록에 없으면 카카오를 부르지 않는다.
     */
    public TokenResponse loginByCode(KakaoCodeLoginRequest request) {
        if (!kakaoProperties.isAllowedRedirectUri(request.getRedirectUri())) {
            throw new MateonException(ErrorCode.KAKAO_AUTH_FAILED);
        }

        String accessToken = kakaoClient.exchangeCode(
          request.getAuthorizationCode(), request.getRedirectUri());
        KakaoUserInfo info = kakaoClient.fetchUserInfo(accessToken);
        return authService.kakaoLogin(info);
    }
}
