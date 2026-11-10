package com.example.mateon.common.config;

import com.example.mateon.common.logging.RequestLoggingFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ForwardedHeaderFilter;

@Configuration(proxyBeanMethods = false)
public class RequestLoggingConfig {

    @Bean
    public FilterRegistrationBean<RequestLoggingFilter> requestLoggingFilterRegistration(RequestLoggingFilter filter) {
        FilterRegistrationBean<RequestLoggingFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setAsyncSupported(true);
        // ASYNC/ERROR 재진입은 중복 기록하지 않는다. 비동기 완료는 AsyncListener 가 처리한다.
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        return registration;
    }

    @Bean
    @ConditionalOnProperty(name = "server.forward-headers-strategy", havingValue = "framework")
    public FilterRegistrationBean<ForwardedHeaderFilter> forwardedHeadersRegistration() {
        FilterRegistrationBean<ForwardedHeaderFilter> registration =
          new FilterRegistrationBean<>(new ForwardedHeaderFilter());
        // Boot 의 자동 등록을 대체한다. X-Forwarded-For 가 제거되기 전에 로깅 필터가 IP 를 보관한다.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        registration.setAsyncSupported(true);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        return registration;
    }
}
