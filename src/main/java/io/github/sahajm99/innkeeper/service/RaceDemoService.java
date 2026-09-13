package io.github.sahajm99.innkeeper.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.github.sahajm99.innkeeper.api.dto.RaceResult;
import io.github.sahajm99.innkeeper.domain.BranchDates;
import io.github.sahajm99.innkeeper.domain.NotFoundException;
import io.github.sahajm99.innkeeper.domain.RoomUnavailableException;
import io.github.sahajm99.innkeeper.model.Booking;
import io.github.sahajm99.innkeeper.model.Branch;
import io.github.sahajm99.innkeeper.model.Room;
import io.github.sahajm99.innkeeper.model.RoomStatus;
import io.github.sahajm99.innkeeper.repository.BranchRepository;
import io.github.sahajm99.innkeeper.repository.RoomRepository;
import io.github.sahajm99.innkeeper.service.AvailabilityService.Night;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Ten guests book the same room for the same night at the same moment, on demand.
 *
 * <p>It exists to be watched. The whole design of this application rests on one claim - that the
 * unique index over (room, night), not the application code, is what stops a double booking - and a
 * claim like that is worth more as a button than as a paragraph. Nine of the ten attempts come back
 * refused because their insert lost, not because anything here checked first.</p>
 *
 * <p>The night is far enough ahead that it cannot collide with a seeded stay and cannot inconvenience
 * anyone reading the demo, and the winner is cancelled as soon as it is decided - inside the free
 * window, so it costs nothing and leaves the invoice void.</p>
 */
@Service
public class RaceDemoService {

    /** Ten racers on ten threads. More would only queue on the connection pool.  */
    public static final int RACERS = 10;

    /** Far enough out that no seeded booking and no visitor is anywhere near it. */
    public static final int DAYS_AHEAD = 200;

    /** How far past that day to look for an empty night before giving up. */
    private static final int HORIZON = AvailabilityService.MAX_DAYS;

    private static final String BRANCH = "DEN";
    private static final String ACTOR = "race";
    private static final int TIMEOUT_SECONDS = 60;

    private static final Logger log = LoggerFactory.getLogger(RaceDemoService.class);

    private final RoomRepository rooms;
    private final BranchRepository branches;
    private final AvailabilityService availability;
    private final BookingService bookings;
    private final Clock clock;

    public RaceDemoService(RoomRepository rooms, BranchRepository branches,
            AvailabilityService availability, BookingService bookings, Clock clock) {
        this.rooms = rooms;
        this.branches = branches;
        this.availability = availability;
        this.bookings = bookings;
        this.clock = clock;
    }

    /**
     * Runs the race and cleans up after it.
     *
     * <p>{@code BookingService.create} runs in a transaction of its own, so ten threads calling it
     * are ten independent units of work; a barrier holds them at the start line so they really do
     * collide rather than politely follow one another.</p>
     */
    public RaceResult run() {
        Room room = firstBookableRoom();
        LocalDate night = firstFreeNight(room);
        CyclicBarrier startLine = new CyclicBarrier(RACERS);

        long startedAt = System.nanoTime();
        List<Booking> created = new ArrayList<>();
        int rejected = 0;
        ExecutorService pool = Executors.newFixedThreadPool(RACERS);
        try {
            for (Future<Booking> attempt :
                    pool.invokeAll(racers(room, night, startLine), TIMEOUT_SECONDS,
                        TimeUnit.SECONDS)) {
                if (lost(attempt)) {
                    rejected++;
                } else {
                    created.add(outcomeOf(attempt));
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The race was interrupted", interrupted);
        } finally {
            pool.shutdownNow();
        }
        long millis = (System.nanoTime() - startedAt) / 1_000_000L;

        String winner = created.isEmpty() ? null : created.get(0).getConfirmationCode();
        releaseTheRoom(winner);
        log.info("race: {} attempts, {} created, {} rejected in {} ms",
            RACERS, created.size(), rejected, millis);
        return new RaceResult(RACERS, created.size(), rejected, winner,
            room.getBranch().getName() + " room " + room.getRoomNumber(), night, millis);
    }

    private List<Callable<Booking>> racers(Room room, LocalDate night, CyclicBarrier startLine) {
        List<Callable<Booking>> racers = new ArrayList<>(RACERS);
        for (int racer = 1; racer <= RACERS; racer++) {
            int number = racer;
            racers.add(() -> {
                startLine.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return bookings.create(new CreateBookingCommand(room.getId(), night,
                    night.plusDays(1), 1, 0, "Racer", "Number " + number,
                    "racer" + number + "@example.com", null, null, ACTOR));
            });
        }
        return racers;
    }

    /** A refusal is the expected outcome for nine of ten; anything else is a real failure. */
    private boolean lost(Future<Booking> attempt) {
        try {
            attempt.get();
            return false;
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof RoomUnavailableException) {
                return true;
            }
            throw new IllegalStateException("A racer failed for a reason that is not the race",
                failure.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The race was interrupted", interrupted);
        }
    }

    private Booking outcomeOf(Future<Booking> attempt) {
        try {
            return attempt.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The race was interrupted", interrupted);
        } catch (ExecutionException impossible) {
            throw new IllegalStateException("A winner that did not win", impossible.getCause());
        }
    }

    /** The winner is cancelled straight away, so the demo leaves the calendar as it found it. */
    private void releaseTheRoom(String winner) {
        if (winner != null) {
            bookings.cancel(winner, ACTOR);
        }
    }

    /**
     * The first room at Denton that is in service. {@code findByStatus} loads the branch with it,
     * which matters here: the branch timezone decides which day is two hundred days away, and
     * open-in-view is off.
     */
    private Room firstBookableRoom() {
        Branch denton = branches.findByCode(BRANCH)
            .orElseThrow(() -> new NotFoundException("No branch " + BRANCH));
        return rooms.findByStatus(RoomStatus.AVAILABLE, denton.getId()).stream()
            .findFirst()
            .orElseThrow(() -> new NotFoundException("No bookable room at branch " + BRANCH));
    }

    private LocalDate firstFreeNight(Room room) {
        LocalDate from = BranchDates.today(clock, room.getBranch().zone()).plusDays(DAYS_AHEAD);
        return availability.strip(room, from, HORIZON).stream()
            .filter(Night::available)
            .findFirst()
            .map(Night::date)
            .orElseThrow(() -> new NotFoundException(
                "No free night for room " + room.getRoomNumber() + " after " + from));
    }
}
