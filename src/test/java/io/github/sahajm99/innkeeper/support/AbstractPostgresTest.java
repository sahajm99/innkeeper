package io.github.sahajm99.innkeeper.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A Spring context wired to a throwaway PostgreSQL 16 container, so the migrations, the schema
 * validation and the concurrency guards are exercised on the database the demo actually deploys
 * to rather than on H2. The container is static and lives here, so every subclass shares one.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfDockerAvailable
@Testcontainers
public abstract class AbstractPostgresTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
}
