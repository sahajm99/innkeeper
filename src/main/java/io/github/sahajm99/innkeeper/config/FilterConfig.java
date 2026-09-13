package io.github.sahajm99.innkeeper.config;

import java.time.Clock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sahajm99.innkeeper.ops.RateLimitFilter;
import io.github.sahajm99.innkeeper.ops.RateLimiter;
import io.github.sahajm99.innkeeper.ops.RequestLoggingFilter;
import io.github.sahajm99.innkeeper.web.ErrorPageRenderer;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Where the two operational filters sit in the chain.
 *
 * <p>Both run in front of Spring Security: the log has to see a request that security refuses, and
 * the limiter should refuse a flood before anything touches a session or the database. They are
 * registered here rather than annotated as components, because a {@code Filter} that is also a bean
 * would be picked up by Spring Boot as well and run twice.</p>
 */
@Configuration(proxyBeanMethods = false)
public class FilterConfig {

    /** Ahead of the security chain, and ahead of the limiter so a refusal still gets logged. */
    public static final int REQUEST_LOGGING_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    public static final int RATE_LIMIT_ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

    @Bean
    public FilterRegistrationBean<RequestLoggingFilter> requestLoggingFilter() {
        FilterRegistrationBean<RequestLoggingFilter> registration =
            new FilterRegistrationBean<>(new RequestLoggingFilter());
        registration.setName("requestLoggingFilter");
        registration.setOrder(REQUEST_LOGGING_ORDER);
        return registration;
    }

    @Bean
    public RateLimiter rateLimiter(InnkeeperProperties properties, Clock clock) {
        return new RateLimiter(properties.rateLimit().perHour(), clock);
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilter(RateLimiter limiter,
            InnkeeperProperties properties, ErrorPageRenderer errorPages, ObjectMapper json) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(
            new RateLimitFilter(limiter, properties, errorPages, json));
        registration.setName("rateLimitFilter");
        registration.setOrder(RATE_LIMIT_ORDER);
        return registration;
    }
}
