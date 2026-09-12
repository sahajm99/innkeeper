package io.github.sahajm99.innkeeper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.time.Instant;
import java.util.Map;

import javax.sql.DataSource;

import io.github.sahajm99.innkeeper.model.Guest;
import io.github.sahajm99.innkeeper.repository.GuestRepository;
import io.github.sahajm99.innkeeper.support.AbstractPostgresTest;
import io.github.sahajm99.innkeeper.support.TestData;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs both migrations against a real PostgreSQL 16 so that anything H2 is lenient about - the
 * identity sequences the seed leaves behind, the unique index behind the double-booking guard,
 * Hibernate's {@code validate} against the real column types - fails here rather than in
 * production.
 */
class PostgresMigrationTest extends AbstractPostgresTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired GuestRepository guests;
    @Autowired DataSource dataSource;

    @Test
    void bothMigrationsApplyToPostgres() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }

        assertThat(jdbc.queryForObject(
            "select count(*) from flyway_schema_history where success = true and version is not null",
            Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
            "select count(*) from flyway_schema_history where success = false", Integer.class))
            .isZero();
    }

    @Test
    void theSeedRowsAreThereOnPostgresToo() {
        assertThat(count("branch")).isEqualTo(3);
        assertThat(count("room")).isEqualTo(32);
        assertThat(count("booking")).isEqualTo(24);
        assertThat(count("room_night")).isEqualTo(51);
        assertThat(count("invoice")).isEqualTo(7);
        assertThat(count("user_account")).isEqualTo(3);
    }

    /**
     * The seed never inserts explicit ids, so the identity sequence is still in step with the rows
     * and the next insert cannot collide with a seeded one. Inserting explicit ids on PostgreSQL
     * would leave the sequence at 1 and break this.
     */
    @Test
    @Transactional
    void aGuestInsertedAfterTheSeedGetsAnIdBeyondEverySeededOne() {
        Long highestSeeded = jdbc.queryForObject("select max(id) from guest", Long.class);

        Guest guest = new Guest();
        guest.setFirstName("New");
        guest.setLastName("Arrival");
        guest.setEmail("new.arrival@example.com");
        guest.setCreatedAt(Instant.now());

        assertThat(guests.saveAndFlush(guest).getId()).isGreaterThan(highestSeeded);
    }

    @Test
    void postgresStillRefusesASecondBookingForTheSameRoomAndNight() {
        Map<String, Object> held = jdbc.queryForMap(
            "select room_id, night_date, booking_id from room_night order by id limit 1");

        assertThatThrownBy(() -> jdbc.update(
                "insert into room_night (room_id, night_date, booking_id) values (?, ?, ?)",
                held.get("room_id"), held.get("night_date"), held.get("booking_id")))
            .isInstanceOf(DataIntegrityViolationException.class)
            .satisfies(failure -> assertThat(TestData.messageChain(failure))
                .containsIgnoringCase("uq_room_night"));
    }

    private int count(String table) {
        Integer rows = jdbc.queryForObject("select count(*) from " + table, Integer.class);
        return rows == null ? 0 : rows;
    }
}
