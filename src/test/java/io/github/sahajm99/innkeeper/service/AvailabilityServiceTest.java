package io.github.sahajm99.innkeeper.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import io.github.sahajm99.innkeeper.domain.BookingStatus;
import io.github.sahajm99.innkeeper.model.Branch;
import io.github.sahajm99.innkeeper.model.Guest;
import io.github.sahajm99.innkeeper.model.Room;
import io.github.sahajm99.innkeeper.model.RoomStatus;
import io.github.sahajm99.innkeeper.model.RoomType;
import io.github.sahajm99.innkeeper.repository.RoomRepository;
import io.github.sahajm99.innkeeper.service.AvailabilityService.Night;
import io.github.sahajm99.innkeeper.service.AvailabilityService.Query;
import io.github.sahajm99.innkeeper.support.AbstractServiceTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * What the rooms page, the room page strip and the search form read: which rooms are free for a
 * stay, which nights of a room are taken, and when the next run of free nights starts.
 */
class AvailabilityServiceTest extends AbstractServiceTest {

    private static final LocalDate SEP_30 = LocalDate.of(2026, 9, 30);
    private static final LocalDate OCT_1 = LocalDate.of(2026, 10, 1);
    private static final LocalDate OCT_3 = LocalDate.of(2026, 10, 3);
    private static final LocalDate OCT_4 = LocalDate.of(2026, 10, 4);
    private static final LocalDate OCT_5 = LocalDate.of(2026, 10, 5);
    private static final LocalDate OCT_20 = LocalDate.of(2026, 10, 20);
    private static final LocalDate OCT_22 = LocalDate.of(2026, 10, 22);

    @Autowired AvailabilityService availability;
    @Autowired RoomRepository rooms;

    private Long branchId;
    private Long standardId;
    private Long closedId;

    /** Room 101 is held for 1 and 2 October and again for 4 October; every other room is free. */
    @BeforeEach
    void createRoomsOfEveryTypeAndBookOneOfThem() {
        inTransaction(data -> {
            Branch denton = data.branch("DEN");
            RoomType standard = data.roomType("STANDARD", 2);
            RoomType deluxe = data.roomType("DELUXE", 3);
            RoomType suite = data.roomType("SUITE", 4);
            Room room101 = data.room(denton, standard, "101", "89.00");
            data.room(denton, deluxe, "201", "129.00");
            data.room(denton, suite, "301", "199.00");
            data.room(denton, suite, "302", "219.00");
            Room closed = data.room(denton, standard, "199", "89.00", RoomStatus.OUT_OF_SERVICE);
            Guest ada = data.guest("ada@example.com");
            data.booking(room101, ada, OCT_1, OCT_3, BookingStatus.CONFIRMED);
            data.booking(room101, ada, OCT_4, OCT_5, BookingStatus.CONFIRMED);
            branchId = denton.getId();
            standardId = room101.getId();
            closedId = closed.getId();
            return null;
        });
    }

    // --- the per-night strip ------------------------------------------------------------------

    @Test
    void theStripMarksTheNightsAlreadyHeldAsUnavailable() {
        List<Night> strip = availability.strip(room(standardId), SEP_30, 6);

        assertThat(strip).extracting(Night::date).containsExactly(
            SEP_30, OCT_1, LocalDate.of(2026, 10, 2), OCT_3, OCT_4, OCT_5);
        assertThat(strip).extracting(Night::available)
            .containsExactly(true, false, false, true, false, true);
    }

    @Test
    void theStripIsClampedToNinetyNightsAndNeverShorterThanOne() {
        assertThat(availability.strip(room(standardId), SEP_30, 365)).hasSize(90);
        assertThat(availability.strip(room(standardId), SEP_30, 90)).hasSize(90);
        assertThat(availability.strip(room(standardId), SEP_30, 0)).hasSize(1);
        assertThat(availability.strip(room(standardId), SEP_30, -3)).hasSize(1);
    }

    @Test
    void aRoomOutOfServiceHasNoAvailableNightAtAll() {
        assertThat(availability.strip(room(closedId), SEP_30, 5))
            .extracting(Night::available)
            .containsOnly(false);
    }

    // --- the next opening ---------------------------------------------------------------------

    @Test
    void theNextOpeningIsTheFirstRunOfFreeNightsThatFitsTheHorizon() {
        assertThat(availability.nextOpening(room(standardId), OCT_1, 1, 30)).contains(OCT_3);
        assertThat(availability.nextOpening(room(standardId), OCT_1, 2, 30)).contains(OCT_5);
        assertThat(availability.nextOpening(room(standardId), SEP_30, 1, 30)).contains(SEP_30);
    }

    @Test
    void thereIsNoNextOpeningWhenTheHorizonIsTooShortOrTheRoomIsOutOfService() {
        assertThat(availability.nextOpening(room(standardId), OCT_1, 2, 3)).isEmpty();
        assertThat(availability.nextOpening(room(closedId), OCT_1, 1, 30)).isEmpty();
        assertThat(availability.nextOpening(room(standardId), OCT_1, 0, 30)).isEmpty();
    }

    // --- the search ---------------------------------------------------------------------------

    @Test
    void aPartyOfFourIsOnlyOfferedSuites() {
        List<Room> found = availability.findAvailable(
            new Query(branchId, null, null, 4, OCT_20, OCT_22));

        assertThat(found).extracting(Room::getRoomNumber).containsExactly("301", "302");
    }

    @Test
    void aRoomHeldForOneOfTheNightsOrOutOfServiceIsNotOffered() {
        List<Room> found = availability.findAvailable(
            new Query(branchId, null, null, 2, OCT_1, OCT_3));

        assertThat(found).extracting(Room::getRoomNumber).containsExactly("201", "301", "302");
    }

    @Test
    void theTypeAndTheMaximumRateNarrowTheSearch() {
        assertThat(availability.findAvailable(
                new Query(branchId, "SUITE", null, 2, OCT_20, OCT_22)))
            .extracting(Room::getRoomNumber).containsExactly("301", "302");
        assertThat(availability.findAvailable(
                new Query(branchId, null, new BigDecimal("130.00"), 2, OCT_20, OCT_22)))
            .extracting(Room::getRoomNumber).containsExactly("101", "201");
    }

    @Test
    void withNoBranchTheSearchCoversEveryBranch() {
        List<Room> found = availability.findAvailable(
            new Query(null, null, null, 2, OCT_20, OCT_22));

        assertThat(found).extracting(Room::getRoomNumber)
            .containsExactly("101", "201", "301", "302");
    }

    private Room room(Long id) {
        return rooms.findById(id).orElseThrow();
    }
}
