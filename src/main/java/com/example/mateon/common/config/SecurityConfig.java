package com.example.mateon.common.config;

import com.example.mateon.auth.jwt.JwtAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    // 로컬/개발 환경에서 모든 오리진을 허용하기 위한 디버그 플래그 (.env 의 debug.enabled=true 로 활성화)
    @Value("${debug.enabled:false}")
    private boolean debugEnabled;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
          .csrf(AbstractHttpConfigurer::disable)
          .cors(cors -> cors.configurationSource(corsConfigurationSource()))
          .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
          .authorizeHttpRequests(auth -> auth
          // SSE(/api/notifications/subscribe) 처럼 비동기로 처리된 요청은 끝날 때 컨테이너가
          // 필터 체인에 ASYNC 로 재진입한다. authorizeHttpRequests 는 모든 DispatcherType 을
          // 인가 검사하는데, JwtAuthenticationFilter 는 OncePerRequestFilter 라 이때 실행되지
          // 않아 SecurityContext 가 비어 있다 → 최초 인가를 통과한 요청이 종료 시점에
          // AuthorizationDeniedException 을 맞는다. 최초 REQUEST 디스패치는 그대로 검사하므로
          // 보안이 느슨해지는 게 아니다.
          .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
          .requestMatchers("/", "/health").permitAll() // 헬스체크 허용
          .requestMatchers("/debug/**").permitAll() // [로컬 전용] 카카오 인가코드 수신 디버그 (컨트롤러는 debug.oauth.enabled 로 격리)
          .requestMatchers("/api/auth/school/**").authenticated() // 학교 인증은 로그인 후 단계 → 인증 필요
          .requestMatchers("/api/auth/**").permitAll() // 그 외 인증 API는 모두 허용
          // "/swagger-ui.html" 은 "/swagger-ui/**" 에 걸리지 않는다 — springdoc 이 여기서
          // /swagger-ui/index.html 로 리다이렉트하므로 진입점도 함께 열어야 한다.
          .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**", "/swagger-resources/**").permitAll()
          .requestMatchers("/ws-stomp/**").permitAll() // WS 핸드셰이크 허용 (인증은 STOMP CONNECT 에서 JWT 검증)
          .requestMatchers("/api/chat/**").authenticated() // 채팅 REST API는 인증 필요
          .requestMatchers("/api/users/**").authenticated() // 사용자 API는 인증 필요
          .requestMatchers("/api/bookmarks/**").authenticated() // 북마크는 전부 내 것이라 인증 필요
          // 포트폴리오는 개인 이력이고 요약 결과가 유저별로 저장된다.
          // 아래 anyRequest().authenticated() 가 이미 잡지만, permitAll 매처를 이 경로에
          // 잘못 추가하는 일이 없도록 의도를 명시해 둔다.
          .requestMatchers("/api/portfolios/**").authenticated()
          .requestMatchers("/api/events/recommended").authenticated() // 추천 API는 인증 필요
          // 활동 등록은 로그인 필요. 아래 permitAll 보다 반드시 위에 있어야 한다
          // (first-match-wins 라, 순서가 뒤집히면 POST 가 비인증으로 열린다).
          .requestMatchers(HttpMethod.POST, "/api/events").authenticated()
          // 포스터 이미지 추출도 등록의 일부다. 이것 역시 아래 permitAll 보다 위여야 한다.
          .requestMatchers(HttpMethod.POST, "/api/events/extract-image").authenticated()
          .requestMatchers("/api/events/**").permitAll() // 기존 Event 조회 API 허용
          // 팀 모집글 조회는 로그인 없이 열어 둔다. TeamController 가 @SecurityRequirement(name = "")
          // 로 선언하고 조회자 기준 필드(isLeader/hasApplied/myApplicationStatus)를 비로그인에서
          // false·null 로 내리도록 이미 구현되어 있는데, 여기 permitAll 이 없어 실제로는 403 이었다.
          //
          // GET 으로 한정한다 — 작성/수정/삭제와 지원은 계속 인증이 필요하다.
          // "/api/teams/*" 의 * 는 슬래시를 넘지 않아 상세 조회(/api/teams/{teamId})까지만 열린다.
          // 지원서·제안·평가는 모두 한 단계 더 깊어(/api/teams/{teamId}/applications,
          // /api/teams/applications/me, /api/teams/offers/me 등) 여기 걸리지 않는다.
          // ** 로 바꾸면 그것들이 통째로 열리므로 넓히지 말 것.
          .requestMatchers(HttpMethod.GET, "/api/teams", "/api/teams/*").permitAll()
          .requestMatchers("/api/matching/**").authenticated() // 의도 추출/추천 API는 인증 필요
          .anyRequest().authenticated()
          )
          .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        if (debugEnabled) {
            // allowCredentials(true) 와 함께 와일드카드를 쓰려면 Origins 가 아닌 OriginPatterns 를 사용해야 한다.
            configuration.setAllowedOriginPatterns(List.of("*"));
        } else {
            configuration.setAllowedOrigins(List.of("http://localhost:3000", "http://localhost:5173"));
        }
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
