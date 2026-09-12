package io.github.sahajm99.innkeeper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.github.sahajm99.innkeeper.domain.BranchDates;
import io.github.sahajm99.innkeeper.domain.RoomUnavailableException;
import io.github.sahajm99.innkeeper.model.Booking;
import io.github.sahajm99.innkeeper.service.BookingService;
import io.github.sahajm99.innkeeper.service.CreateBookingCommand;
import io.github.sahajm99.innkeeper.support.AbstractPostgresTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * The same race as {@code BookingRaceTest}, run against PostgreSQL 16 rather than H2, because the
 * whole double-booking guard rests on the database honouring a unique index under concurrent
 * inserts and that is the database the demo deploys to.
 *
 * <p>This one runs on the seeded container, so it picks a room the seed left free 60 days out and
 * removes the winner afterwards: {@code PostgresMigrationTest} asserts exact seed row counts and
 * the two classes share the container whichever order they run in.</p>
 *
 * <p>Hibernate logs each of the seven losing inserts through {@code SqlExceptionHelper} before the
 * service translates it, which is expected rather than a failure, so that logger is off here.</p>
 */
@TestPropertySource(properties = "logging.level.org.hibernate.engine.jdbc.spi.SqlExceptionHelper=off")
class PostgresBookingRaceTest extends AbstractPostgresTest {

    private static final int RACERS = 8;
    private static final ZoneId ZONE = ZoneId.of("America/Chicago");

    @Autowired BookingService bookings;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    private long highestSeededBooking;
    private long highestSeededGuest;
    private Long roomId;
    private LocalDate checkIn;
    private LocalDate checkOut;

    @BeforeEach
    void findARoomTheSeedLeftFree() {
        highestSeededBooking = maxId("booking");
        highestSeededGuest = maxId("guest");
        LocalDate today = BranchDates.today(clock, ZONE);
        checkIn = today.plusDays(60);
        checkOut = today.plusDays(62);
        roomId = jdbc.queryForObject("""
            select r.id from room r
            where r.status = 'AVAILABLE'
              and not exists (select 1 from room_night n
                              where n.room_id = r.id and n.night_date >= ? and n.night_date < ?)
            order by r.id limit 1""", Long.class, checkIn, checkOut);
        assertThat(roomId).isNotNull();
    }

    @AfterEach
    void putTheSeedBackAsItWas() {
        jdbc.update("delete from booking where id > ?", highestSeededBooking);
        jdbc.update("delete from guest where id > ?", highestSeededGuest);
    }

    @Test
    void postgresLetsExactlyOneOfEightSimultaneousBookingsThrough() throws Exception {
        CyclicBarrier startLine = new CyclicBarrier(RACERS);
        List<Callable<Booking>> racers = new ArrayList<>();
        for (int racer = 0; racer < RACERS; racer++) {
            String email = "racer" + racer + "@example.com";
            racers.add(() -> {
                startLine.await(30, TimeUnit.SECONDS);
                return bookings.create(new CreateBookingCommand(roomId, checkIn, checkOut, 2, 0,
                    "Racer", "Hopeful", email, null, null, "guest"));
            });
        }

        ExecutorService pool = Executors.newFixedThreadPool(RACERS);
        List<Booking> created = new ArrayList<>();
        List<Throwable> refused = new ArrayList<>();
        try {
            for (Future<Booking> outcome : pool.invokeAll(racers, 60, TimeUnit.SECONDS)) {
                try {
                    created.add(outcome.get());
                } catch (ExecutionException failure) {
                    refused.add(failure.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(created).hasSize(1);
        assertThat(refused).hasSize(RACERS - 1)
            .allSatisfy(failure -> assertThat(failure).isInstanceOf(RoomUnavailableException.class));
        assertThat(jdbc.queryForObject(
            "select count(*) from room_night where room_id = ? and night_date >= ? and night_date < ?",
            Integer.class, roomId, checkIn, checkOut)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from booking where id > ?",
            Integer.class, highestSeededBooking)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from guest where id > ?",
            Integer.class, highestSeededGuest)).isEqualTo(1);
    }

    /**
     * The high water mark of a table's ids. Rows above it are this test's, which is how the seed
     * is put back afterwards - and why the losers are counted by rows rather than by id: PostgreSQL
     * hands out identity values outside the transaction, so seven of the eight are simply burnt.
     */
    private long maxId(String table) {
        Long highest = jdbc.queryForObject(
            "select coalesce(max(id), 0) from " + table, Long.class);
        return highest == null ? 0 : highest;
    }
}
