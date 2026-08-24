package com.example.mateon.common.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 브라우저에서 이 서버로 붙을 수 있는 오리진 목록. (JwtProperties 와 같은 형태)
 *
 * <p>
 * 이전에는 목록이 SecurityConfig/WebSocketConfig 안에 문자열로 박혀 있었고, debug.enabled
 * 하나로 "전체 허용" 아니면 "localhost 두 개" 로만 갈렸다. 그래서 배포 환경에서 실제 프론트
 * 도메인을 넣을 자리가 아예 없었다 — 운영을 열려면 전체 허용밖에 방법이 없었다는 뜻이다.
 * 기본값을 프로필이 정하고(application-dev.yml / application-prod.yml) 환경변수로 덮는다.
 *
 * <p>
 * REST(SecurityConfig)와 STOMP 핸드셰이크(WebSocketConfig)가 같은 값을 봐야 한다. 예전에는
 * 두 곳이 같은 상수를 각자 들고 있어서 한쪽만 고치면 채팅만 조용히 막혔다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "cors")
public class CorsProperties {

    /**
     * 허용 오리진. 스킴과 포트까지 정확히 적는다("https://example.com").
     *
     * <p>
     * 이 값을 쓰는 쪽은 setAllowedOrigins 가 아니라 setAllowedOriginPatterns 로 넘긴다.
     * 정확한 문자열도 패턴으로 그대로 동작하고, 로컬의 "*" 도 같은 코드로 처리되기 때문이다
     * (allowCredentials(true) 와 함께 와일드카드를 쓰려면 Origins 가 아닌 Patterns 여야 한다).
     *
     * <p>
     * 기본값은 로컬 프론트 두 개다. 다만 프로필 파일이 항상 값을 주므로 실제로 이 기본값이
     * 쓰이는 건 프로필 없이 뜨는 경우뿐이다.
     */
    private List<String> allowedOrigins = List.of("http://localhost:3000", "http://localhost:5173");

    /**
     * 오리진 설정 누락을 부팅 시점에 잡는다. (AiServerProperties.validateInternalSecret 과 같은 이유)
     *
     * <p>
     * application-prod.yml 은 일부러 기본값 없이 "${CORS_ALLOWED_ORIGINS}" 만 적어 둔다. 그런데
     * @ConfigurationProperties 바인딩은 @Value 와 달리 해결하지 못한 플레이스홀더를 예외 없이
     * 무시하고 원문을 그대로 넣기 때문에, 그것만으로는 부팅이 막히지 않는다. 그 리터럴이 허용
     * 오리진 하나로 등록되면 어떤 브라우저 요청도 통과하지 못하는데, 서버는 멀쩡히 떠 있고
     * 로그도 조용하다 — 프론트만 전부 CORS 오류를 맞는다. 그건 원인을 찾기 가장 나쁜 형태다.
     *
     * <p>
     * 빈 목록도 같이 막는다. 이 서버는 브라우저에서만 쓰이므로 오리진이 하나도 없는 설정은
     * 의도일 수 없다.
     */
    @PostConstruct
    void validateAllowedOrigins() {
        boolean unresolved = allowedOrigins.stream()
          .anyMatch(origin -> !StringUtils.hasText(origin) || origin.startsWith("${"));
        if (allowedOrigins.isEmpty() || unresolved) {
            throw new IllegalStateException(
              "cors.allowed-origins 가 설정되지 않았습니다. .env 에 CORS_ALLOWED_ORIGINS 를 추가하세요. "
              + "(콤마로 구분하고 스킴·포트까지 정확히 적습니다. "
              + "예: https://mateon.example.com,https://www.mateon.example.com)");
        }
    }
}
