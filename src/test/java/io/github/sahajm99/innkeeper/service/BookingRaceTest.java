package io.github.sahajm99.innkeeper.service;

import static org.assertj.core.api.Assertions.assertThat;

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
import io.github.sahajm99.innkeeper.model.Branch;
import io.github.sahajm99.innkeeper.model.RoomType;
import io.github.sahajm99.innkeeper.support.AbstractServiceTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The point of the whole design: eight guests book the same room for the same two nights at the
 * same moment and the database, not the application, decides who gets it.
 *
 * <p>Seven of the eight lose on the unique index over (room_id, night_date). Their transactions
 * roll back whole, so the guest rows they inserted a moment earlier go with them - which is why
 * the guest count is asserted as well as the booking count.</p>
 */
class BookingRaceTest extends AbstractServiceTest {

    private static final int RACERS = 8;
    private static final ZoneId ZONE = ZoneId.of("America/Chicago");

    @Autowired BookingService bookings;

    private Long roomId;
    private LocalDate checkIn;
    private LocalDate checkOut;

    @BeforeEach
    void createTheOneRoomEveryoneWants() {
        LocalDate today = BranchDates.today(clock, ZONE);
        checkIn = today.plusDays(60);
        checkOut = today.plusDays(62);
        inTransaction(data -> {
            Branch denton = data.branch("DEN");
            RoomType standard = data.roomType("STANDARD", 2);
            roomId = data.room(denton, standard, "101", "89.00").getId();
            return null;
        });
    }

    @Test
    void eightSimultaneousBookingsForOneRoomLeaveExactlyOneWinner() throws Exception {
        int guestsBefore = count("guest");
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
        assertThat(count("booking")).isEqualTo(1);
        assertThat(count("guest")).isEqualTo(guestsBefore + 1);
        assertThat(nightsHeld(roomId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
            "select count(*) from room_night where room_id = ? and night_date >= ? and night_date < ?",
            Integer.class, roomId, checkIn, checkOut)).isEqualTo(2);
    }
}
