package com.example.mateon.config;

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
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-resources/**").permitAll()
                        .requestMatchers("/ws-stomp/**").permitAll() // WS 핸드셰이크 허용 (인증은 STOMP CONNECT 에서 JWT 검증)
                        .requestMatchers("/api/chat/**").authenticated() // 채팅 REST API는 인증 필요
                        .requestMatchers("/api/users/**").authenticated() // 사용자 API는 인증 필요
                        .requestMatchers("/api/events/recommended").authenticated() // 추천 API는 인증 필요
                        // 활동 등록은 로그인 필요. 아래 permitAll 보다 반드시 위에 있어야 한다
                        // (first-match-wins 라, 순서가 뒤집히면 POST 가 비인증으로 열린다).
                        .requestMatchers(HttpMethod.POST, "/api/events").authenticated()
                        .requestMatchers("/api/events/**").permitAll() // 기존 Event 조회 API 허용
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