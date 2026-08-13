package com.example.mateon.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API 명세의 정본.
 *
 * <p>
 * 이전에는 명세가 손으로 쓴 마크다운(docs/api-spec)뿐이었다. 코드와 별개 출처라 조용히
 * 어긋났고, 실제로 v7 개정에서 서버가 내려주지 않는 필드 세 개가 문서에 들어간 채 v8 까지
 * 복사됐다. 여기서 만드는 스키마는 DTO 클래스에서 직접 생성되므로 같은 어긋남이 구조적으로
 * 불가능하다 — 필드를 지우면 문서에서도 사라진다.
 *
 * <p>
 * 인증 방식을 여기 한 번만 선언한다. 마크다운 명세는 엔드포인트마다 Authorization 헤더 표를
 * 손으로 반복하고 있었는데, 그건 빠뜨리기도 틀리기도 쉽다.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI mateonOpenAPI() {
        return new OpenAPI()
          .info(new Info()
            .title("Mateon API")
            .version("v8")
            .description("""
                                교내 활동/공모전 팀 매칭 서비스 API.

                                인증이 필요한 엔드포인트는 로그인(`POST /api/auth/login`)으로 받은
                                accessToken 을 우측 상단 Authorize 에 넣으면 그대로 호출해 볼 수 있다.

                                모든 응답은 `{ success, message, data }` 로 감싸인다."""))
          .components(new Components().addSecuritySchemes(BEARER_SCHEME,
            new SecurityScheme()
              .type(SecurityScheme.Type.HTTP)
              .scheme("bearer")
              .bearerFormat("JWT")
              .description("로그인 응답의 accessToken. `Bearer ` 접두어는 UI 가 붙인다.")))
          // 기본을 '인증 필요'로 둔다 — SecurityConfig 의 anyRequest().authenticated() 와 같은 방향이다.
          .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
