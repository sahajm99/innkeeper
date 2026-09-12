package io.github.sahajm99.innkeeper.support;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Random;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The two sources of non-determinism the service tests take control of: the clock every policy
 * reads, and the source of confirmation codes.
 *
 * <p>Both are {@code @Primary}, so they win over the application's own {@code Clock} bean and over
 * the secure random {@code BookingService} falls back to when no {@link Random} bean exists.</p>
 */
@TestConfiguration(proxyBeanMethods = false)
public class ServiceTestBeans {

    /** Where every service test starts: 10 am on 12 September 2026 in America/Chicago. */
    public static final Instant NOW = Instant.parse("2026-09-12T15:00:00Z");

    /**
     * Named so it does not collide with the application's own {@code clock} bean: bean definition
     * overriding is off, so this has to sit beside that one and win on {@code @Primary} instead.
     */
    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(NOW, ZoneOffset.UTC);
    }

    /** Seedable, so a test can force the confirmation code collision the retry loop exists for. */
    @Bean
    @Primary
    public Random confirmationCodeRandom() {
        return new Random();
    }
}
