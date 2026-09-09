package com.example.mateon.common.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 웹 React 가 받는 값. 비밀번호 찾기 메일의 링크 목적지가 이 서버가 아니라
 * 웹 프론트여야 해서 따로 둔다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /**
     * 웹 React origin. 끝에 슬래시를 붙이지 않는다.
     *
     * <p>
     * prod 는 기본값 없이 {@code ${WEB_BASE_URL}} 만 적는다. 비우면 메일이 localhost 로
     * 나간다. 아래 검증이 부팅을 막는다.
     */
    private String webBaseUrl = "http://localhost:5173";

    /**
     * 비밀번호 재설정 페이지 경로. React 라우트와 같아야 한다.
     */
    private String webResetPasswordPath = "/reset-password";

    private Duration passwordResetTtl = Duration.ofMinutes(30);

    private Duration passwordResetCooldown = Duration.ofSeconds(60);

    @PostConstruct
    void validateWebBaseUrl() {
        if (!StringUtils.hasText(webBaseUrl) || webBaseUrl.startsWith("${")) {
            throw new IllegalStateException(
              "app.web-base-url 이 설정되지 않았습니다. .env 에 WEB_BASE_URL 을 추가하세요. "
              + "(비밀번호 찾기 메일이 열리는 웹 React 주소입니다. "
              + "예: https://mateon.example.com)");
        }
    }

    /**
     * 메일 링크. 백엔드 호스트가 아니라 웹 React 다.
     */
    public String passwordResetLink(String rawToken) {
        String base = webBaseUrl.endsWith("/") ? webBaseUrl.substring(0, webBaseUrl.length() - 1) : webBaseUrl;
        String path = webResetPasswordPath.startsWith("/") ? webResetPasswordPath : "/" + webResetPasswordPath;
        return base + path + "?token=" + rawToken;
    }
}
