package com.example.mateon.auth.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 카카오 REST 연동 설정. 웹 인가코드 교환에만 쓰인다. RN 액세스 토큰 경로는 이 값을
 * 보지 않는다.
 *
 * <p>
 * redirect-uris 는 웹이 보낸 redirectUri 를 그대로 카카오 교환에 넣기 전에 대조하는
 * 허용 목록이다. 카카오가 코드를 그 URI 에 묶어 두므로 서버도 같은 값을 써야 하고,
 * 목록에 없는 URI 로 교환을 시도하면 토큰이 엉뚱한 곳으로 샐 수 있다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "kakao")
public class KakaoProperties {

    /**
     * 카카오 REST API 키. 인가코드 교환의 client_id. 비어 있으면 코드 플로우만 실패하고
     * 앱은 뜬다 (RN 경로는 이 키가 필요 없다).
     */
    private String restApiKey = "";

    /**
     * 카카오 client secret. 콘솔에서 켜 둔 앱만 필요하다.
     */
    private String clientSecret = "";

    /**
     * 허용하는 웹 콜백 URI. 콤마로 구분. React 콜백과 글자 단위로 같아야 한다.
     *
     * <p>
     * 기본값은 로컬 Vite. prod 프로필은 기본값 없이 {@code ${KAKAO_REDIRECT_URIS}} 만
     * 적으므로, 비우면 아래 검증이 부팅을 막는다.
     */
    private List<String> redirectUris = List.of("http://localhost:5173/auth/kakao/callback");

    @PostConstruct
    void validateRedirectUris() {
        boolean unresolved = redirectUris.stream()
          .anyMatch(uri -> !StringUtils.hasText(uri) || uri.startsWith("${"));
        if (redirectUris.isEmpty() || unresolved) {
            throw new IllegalStateException(
              "kakao.redirect-uris 가 설정되지 않았습니다. .env 에 KAKAO_REDIRECT_URIS 를 추가하세요. "
              + "(웹 React 카카오 콜백과 글자 단위로 같아야 합니다. "
              + "예: https://mateon.example.com/auth/kakao/callback)");
        }
    }

    public boolean isAllowedRedirectUri(String redirectUri) {
        if (!StringUtils.hasText(redirectUri)) {
            return false;
        }
        return redirectUris.stream().anyMatch(redirectUri::equals);
    }
}
