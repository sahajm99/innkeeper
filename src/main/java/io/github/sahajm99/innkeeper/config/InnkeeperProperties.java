package io.github.sahajm99.innkeeper.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "innkeeper")
public record InnkeeperProperties(String resetToken, RateLimit rateLimit, String timezone) {

    public record RateLimit(boolean enabled, int perHour) {
    }
}
