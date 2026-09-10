package com.example.mateon.auth.client;

import com.example.mateon.auth.config.KakaoProperties;
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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * 카카오 REST 클라이언트.
 *
 * <p>
 * RN 은 네이티브 SDK 가 준 access token 으로 {@link #fetchUserInfo} 만 탄다. 웹은 인가코드를
 * {@link #exchangeCode} 로 토큰으로 바꾼 뒤 같은 user/me 를 탄다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoOAuthClient {

    private static final String KAKAO_USER_ME_URL = "https://kapi.kakao.com/v2/user/me";
    private static final String KAKAO_TOKEN_URL = "https://kauth.kakao.com/oauth/token";

    private final RestTemplate restTemplate;
    private final KakaoProperties kakaoProperties;

    /**
     * 웹 React 콜백이 받은 인가코드를 카카오 액세스 토큰으로 교환한다.
     *
     * @param redirectUri 카카오 authorize 에 넘긴 값과 글자 단위로 같아야 한다. 허용 목록
     * 대조는 {@link com.example.mateon.auth.service.KakaoLoginService} 가 먼저 한다.
     */
    @SuppressWarnings("unchecked")
    public String exchangeCode(String authorizationCode, String redirectUri) {
        if (!StringUtils.hasText(kakaoProperties.getRestApiKey())) {
            log.warn("카카오 REST API 키가 없어 인가코드를 교환할 수 없습니다");
            throw new MateonException(ErrorCode.KAKAO_AUTH_FAILED);
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "authorization_code");
            form.add("client_id", kakaoProperties.getRestApiKey());
            form.add("redirect_uri", redirectUri);
            form.add("code", authorizationCode);
            if (StringUtils.hasText(kakaoProperties.getClientSecret())) {
                form.add("client_secret", kakaoProperties.getClientSecret());
            }

            ResponseEntity<Map> response = restTemplate.exchange(
              KAKAO_TOKEN_URL,
              HttpMethod.POST,
              new HttpEntity<>(form, headers),
              Map.class
            );

            Map<String, Object> body = response.getBody();
            if (body == null || body.get("access_token") == null) {
                log.warn("카카오 token 응답에 access_token 없음: {}", body);
                throw new MateonException(ErrorCode.KAKAO_AUTH_FAILED);
            }
            return body.get("access_token").toString();

        } catch (MateonException e) {
            throw e;
        } catch (RestClientResponseException e) {
            log.warn("카카오 token 교환 실패: status={}, body={}",
              e.getStatusCode(), e.getResponseBodyAsString());
            throw new MateonException(ErrorCode.KAKAO_AUTH_FAILED);
        } catch (Exception e) {
            log.warn("카카오 token 교환 중 예외", e);
            throw new MateonException(ErrorCode.KAKAO_AUTH_FAILED);
        }
    }

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
