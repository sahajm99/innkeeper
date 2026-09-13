package io.github.sahajm99.innkeeper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.github.sahajm99.innkeeper.domain.BranchDates;
import io.github.sahajm99.innkeeper.domain.NotFoundException;
import io.github.sahajm99.innkeeper.model.AppMetadata;
import io.github.sahajm99.innkeeper.model.Booking;
import io.github.sahajm99.innkeeper.model.Room;
import io.github.sahajm99.innkeeper.repository.RoomRepository;
import io.github.sahajm99.innkeeper.seed.SeedData;
import io.github.sahajm99.innkeeper.service.ResetService.Outcome;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The nightly reset, against the seeded database every Spring Boot test in this JVM shares.
 *
 * <p>Every test here leaves that database freshly seeded, which is what makes them safe to run in
 * any order beside the tests that assert on the seed.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class ResetServiceTest {

    private static final ZoneId ZONE = ZoneId.of("America/Chicago");
    private static final Duration HALF_AN_HOUR = Duration.ofMinutes(30);

    @Autowired ResetService reset;
    @Autowired BookingService bookings;
    @Autowired RoomRepository rooms;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    @Test
    void resettingThrowsAwayEverythingTheDemoDidAndSeedsAgain() {
        Booking extra = anExtraBooking();
        assertThat(count("booking")).isEqualTo(SeedData.expectedCounts().bookings() + 1);

        Outcome outcome = reset.reset("test");

        assertThat(outcome.performed()).isTrue();
        assertThat(count("booking")).isEqualTo(SeedData.expectedCounts().bookings());
        assertThat(count("room_night")).isEqualTo(SeedData.expectedCounts().roomNights());
        assertThat(count("guest")).isEqualTo(SeedData.expectedCounts().guests());
        assertThatThrownBy(() -> bookings.requireByCode(extra.getConfirmationCode()))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void aResetRecordsWhenItHappened() {
        Instant before = clock.instant();

        Outcome outcome = reset.reset("test");

        assertThat(outcome.lastResetAt()).isNotNull().isAfterOrEqualTo(before);
        assertThat(reset.lastResetAt()).contains(outcome.lastResetAt());
        assertThat(reset.seededAt()).contains(outcome.lastResetAt());
    }

    @Test
    void aSecondResetWithinHalfAnHourIsSkipped() {
        reset.reset("test");

        Outcome skipped = reset.resetIfStale(HALF_AN_HOUR, "scheduler");

        assertThat(skipped.performed()).isFalse();
        assertThat(skipped.reason()).isEqualTo("recent");
        assertThat(skipped.lastResetAt()).isNotNull();
    }

    @Test
    void aDeploymentLeftOvernightIsResetBySchedule() {
        reset.reset("test");
        jdbc.update("update app_metadata set meta_value = ? where meta_key = ?",
            clock.instant().minus(Duration.ofHours(9)).toString(), AppMetadata.LAST_RESET_AT);

        Outcome performed = reset.resetIfStale(HALF_AN_HOUR, "scheduler");

        assertThat(performed.performed()).isTrue();
    }

    @Test
    void twoResetsAtOnceLeaveOneOfThemBusy() throws Exception {
        CyclicBarrier startLine = new CyclicBarrier(2);
        List<Callable<Outcome>> both = List.of(racer(startLine), racer(startLine));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Outcome> outcomes;
        try {
            outcomes = pool.invokeAll(both, 60, TimeUnit.SECONDS).stream()
                .map(this::outcomeOf)
                .toList();
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(outcomes).filteredOn(Outcome::performed).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> !outcome.performed())
            .singleElement()
            .satisfies(busy -> assertThat(busy.reason()).isEqualTo("busy"));
        assertThat(count("booking")).isEqualTo(SeedData.expectedCounts().bookings());
    }

    private Callable<Outcome> racer(CyclicBarrier startLine) {
        return () -> {
            startLine.await(30, TimeUnit.SECONDS);
            return reset.reset("race");
        };
    }

    private Outcome outcomeOf(Future<Outcome> future) {
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for a reset", interrupted);
        } catch (Exception failure) {
            throw new IllegalStateException("A reset call did not finish", failure);
        }
    }

    /** A booking far enough ahead that it collides with nothing the seed put in the calendar. */
    private Booking anExtraBooking() {
        Room room = rooms.findByBranchCodeAndRoomNumber("DEN", "101").orElseThrow();
        LocalDate checkIn = BranchDates.today(clock, ZONE).plusDays(300);
        return bookings.create(new CreateBookingCommand(room.getId(), checkIn, checkIn.plusDays(2),
            2, 0, "Extra", "Booking", "extra.booking@example.com", null, null, "test"));
    }

    private int count(String table) {
        Integer rows = jdbc.queryForObject("select count(*) from " + table, Integer.class);
        return rows == null ? 0 : rows;
    }
}
