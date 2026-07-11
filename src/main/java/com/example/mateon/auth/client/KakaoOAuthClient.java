package com.example.mateon.auth.client;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * 카카오 액세스 토큰으로 사용자 정보를 조회하는 클라이언트.
 * RN 이 카카오 네이티브 SDK 로 받은 access token 을 그대로 넘겨받아 /v2/user/me 만 호출한다.
 * (REST API 키·redirect URI 불필요 - 토큰 자체를 카카오가 검증한다.)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoOAuthClient {

    private final RestTemplate restTemplate;

    private static final String KAKAO_USER_ME_URL = "https://kapi.kakao.com/v2/user/me";

    @SuppressWarnings("unchecked")
    public KakaoUserInfo fetchUserInfo(String accessToken) {
        try {
            // 카카오 문서 기준 정규 호출: POST + Bearer 토큰 + form 콘텐츠 타입.
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            ResponseEntity<Map> response = restTemplate.exchange(
                    KAKAO_USER_ME_URL,
                    HttpMethod.POST,
                    new HttpEntity<>(headers),
                    Map.class
            );

            Map<String, Object> body = response.getBody();
            if (body == null || body.get("id") == null) {
                log.warn("카카오 user/me 응답에 id 없음: {}", body);
                throw new MateonException(ErrorCode.KAKAO_AUTH_FAILED);
            }

            // providerId: 카카오 회원번호 (Long 으로 오므로 문자열로 변환)
            String providerId = String.valueOf(body.get("id"));

            // kakao_account: 이메일/닉네임 등 동의 항목이 들어있는 하위 객체 (동의 안 하면 없을 수 있음)
            Map<String, Object> kakaoAccount = (Map<String, Object>) body.get("kakao_account");

            String email = null;
            boolean emailVerified = false;
            String nickname = null;

            if (kakaoAccount != null) {
                Object emailObj = kakaoAccount.get("email");
                email = emailObj != null ? emailObj.toString() : null;

                Object verifiedObj = kakaoAccount.get("is_email_verified");
                emailVerified = Boolean.TRUE.equals(verifiedObj);

                Map<String, Object> profile = (Map<String, Object>) kakaoAccount.get("profile");
                if (profile != null && profile.get("nickname") != null) {
                    nickname = profile.get("nickname").toString();
                }
            }

            return new KakaoUserInfo(providerId, email, emailVerified, nickname);

        } catch (MateonException e) {
            throw e;
        } catch (RestClientResponseException e) {
            // 카카오가 4xx/5xx 로 응답(토큰 만료·위조 등) → 실제 상태/본문을 로그로 남긴다.
            log.warn("카카오 user/me 호출 실패: status={}, body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new MateonException(ErrorCode.KAKAO_AUTH_FAILED);
        } catch (Exception e) {
            // 네트워크/파싱 등 기타 오류
            log.warn("카카오 user/me 호출 중 예외", e);
            throw new MateonException(ErrorCode.KAKAO_AUTH_FAILED);
        }
    }
}
