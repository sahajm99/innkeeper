package io.github.sahajm99.innkeeper.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A Spring context wired to a throwaway PostgreSQL 16 container, so the migrations, the schema
 * validation and the concurrency guards are exercised on the database the demo actually deploys
 * to rather than on H2.
 *
 * <p>The container is a singleton started once in the static initializer rather than a
 * {@code @Container} field: the Testcontainers JUnit extension stops the container after the last
 * test of each concrete class, while Spring caches its context across subclasses, so a second
 * subclass would reuse a datasource pointing at a stopped container. Nothing stops it here - the
 * Testcontainers reaper (Ryuk) removes it when the JVM exits.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfDockerAvailable
public abstract class AbstractPostgresTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        postgres.start();
    }
}
