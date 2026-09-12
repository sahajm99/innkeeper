package io.github.sahajm99.innkeeper.support;

import java.util.function.Function;

import io.github.sahajm99.innkeeper.seed.SeedData;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The context the booking, availability and invoice tests share.
 *
 * <p>It is deliberately not the seeded {@code innkeeper} database every other
 * {@code @SpringBootTest} runs against. These tests replace the {@code Clock} with a fixed one, and
 * whichever context starts first is the one that applies the V2 seed - so sharing the database
 * would let a fake clock decide what "today" means for the tests that assert on the seed. This
 * context gets its own H2 database with only {@code V1__schema.sql} applied, and every test builds
 * exactly the rooms it needs with {@link TestData}.</p>
 *
 * <p>The service tests cannot be {@code @Transactional}: they need real commits, because the
 * behaviour under test - the room-night guard, the invoice rebuild, the losers of a race leaving
 * no rows - only happens at a transaction boundary. Each test therefore empties the database
 * first rather than rolling back.</p>
 *
 * <p>Several of these tests provoke a unique violation on purpose - a colliding confirmation code,
 * seven losers of a race - and Hibernate logs every one of them through {@code SqlExceptionHelper}
 * before the service translates it. That logger is off here so the expected noise does not look
 * like a failure; the assertions, not the log, are what report a real one.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(ServiceTestBeans.class)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:innkeeper-service;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
        + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000",
    "spring.flyway.target=1",
    "logging.level.org.hibernate.engine.jdbc.spi.SqlExceptionHelper=off"
})
public abstract class AbstractServiceTest {

    @Autowired protected MutableClock clock;
    @Autowired protected JdbcTemplate jdbc;
    @PersistenceContext protected EntityManager entityManager;

    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactions;

    @BeforeEach
    void emptyTheDatabaseAndResetTheClock() {
        transactions = new TransactionTemplate(transactionManager);
        clock.reset();
        new SeedData(jdbc, clock).deleteAll();
    }

    /** Runs the block in its own committed transaction with a {@link TestData} bound to it. */
    protected <T> T inTransaction(Function<TestData, T> block) {
        return transactions.execute(status -> {
            T value = block.apply(new TestData(entityManager));
            entityManager.flush();
            return value;
        });
    }

    protected int count(String table) {
        Integer rows = jdbc.queryForObject("select count(*) from " + table, Integer.class);
        return rows == null ? 0 : rows;
    }

    protected int nightsHeld(Long roomId) {
        Integer rows = jdbc.queryForObject(
            "select count(*) from room_night where room_id = ?", Integer.class, roomId);
        return rows == null ? 0 : rows;
    }
}
