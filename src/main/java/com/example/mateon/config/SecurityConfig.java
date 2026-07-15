package com.example.mateon.config;

import com.example.mateon.auth.jwt.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
                        .requestMatchers("/", "/health").permitAll() // 헬스체크 허용
                        .requestMatchers("/debug/**").permitAll() // [로컬 전용] 카카오 인가코드 수신 디버그 (컨트롤러는 debug.oauth.enabled 로 격리)
                        .requestMatchers("/api/auth/school/**").authenticated() // 학교 인증은 로그인 후 단계 → 인증 필요
                        .requestMatchers("/api/auth/**").permitAll() // 그 외 인증 API는 모두 허용
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-resources/**").permitAll()
                        .requestMatchers("/ws-stomp/**").permitAll() // WS 핸드셰이크 허용 (인증은 STOMP CONNECT 에서 JWT 검증)
                        .requestMatchers("/api/chat/**").authenticated() // 채팅 REST API는 인증 필요
                        .requestMatchers("/api/users/**").authenticated() // 사용자 API는 인증 필요
                        .requestMatchers("/api/events/recommended").authenticated() // 추천 API는 인증 필요
                        .requestMatchers("/api/events/**").permitAll() // 기존 Event API 허용
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