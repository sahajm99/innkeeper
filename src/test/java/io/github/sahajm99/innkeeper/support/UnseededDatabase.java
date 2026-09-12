package io.github.sahajm99.innkeeper.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.test.context.TestPropertySource;

/**
 * Points a JPA slice test at its own H2 database with only {@code V1__schema.sql} applied.
 *
 * <p>The {@code test} profile runs on one in-memory database shared by every context in the JVM,
 * and the {@code @SpringBootTest} contexts let the V2 migration seed it. A slice test builds its
 * own fixtures - its own Denton branch, its own bookings - and would collide with those rows, so
 * it gets a separate database and a Flyway target of 1.</p>
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:innkeeper-slice;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
        + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
    "spring.flyway.target=1"
})
public @interface UnseededDatabase {
}
