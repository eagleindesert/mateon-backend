package com.example.mateon.common.config;

import com.example.mateon.common.logging.RequestLoggingFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.server.autoconfigure.servlet.ServletWebServerConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.web.filter.ForwardedHeaderFilter;

import static org.assertj.core.api.Assertions.assertThat;

class RequestLoggingConfigTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
      .withUserConfiguration(RequestLoggingConfig.class, RequestLoggingFilter.class)
      .withConfiguration(AutoConfigurations.of(ServletWebServerConfiguration.class));

    @Test
    void loggingRunsBeforeExactlyOneForwardedHeaderFilter() {
        runner.withPropertyValues("server.forward-headers-strategy=framework").run(context -> {
            assertThat(context).hasNotFailed();
            var registrations = context.getBeansOfType(FilterRegistrationBean.class).values();
            assertThat(registrations.stream()
              .filter(registration -> registration.getFilter() instanceof ForwardedHeaderFilter)).hasSize(1);
            FilterRegistrationBean<?> logging = context.getBean("requestLoggingFilterRegistration", FilterRegistrationBean.class);
            FilterRegistrationBean<?> forwarded = context.getBean("forwardedHeadersRegistration", FilterRegistrationBean.class);
            assertThat(logging.getOrder()).isLessThan(forwarded.getOrder());
            assertThat(logging.isAsyncSupported()).isTrue();
        });
    }

    @Test
    void disabledForwardingDoesNotDisableRequestLogging() {
        runner.withPropertyValues("server.forward-headers-strategy=none").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("requestLoggingFilterRegistration");
            assertThat(context.getBeansOfType(FilterRegistrationBean.class).values().stream()
              .filter(registration -> registration.getFilter() instanceof ForwardedHeaderFilter)).isEmpty();
        });
    }
}
