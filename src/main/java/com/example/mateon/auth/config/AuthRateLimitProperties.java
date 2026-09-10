package com.example.mateon.auth.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "auth.rate-limit")
public class AuthRateLimitProperties {

    private Duration window = Duration.ofMinutes(10);

    private int ipPerWindow = 30;

    private int loginEmailPerWindow = 10;

    private int resetEmailPerWindow = 3;
}
