package io.github.sahajm99.innkeeper.repository;

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
import io.github.sahajm99.innkeeper.support.TestData;
import io.github.sahajm99.innkeeper.support.UnseededDatabase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@UnseededDatabase
class RoomRepositoryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    private static final LocalDate SEP_28 = LocalDate.of(2026, 9, 28);
    private static final LocalDate SEP_30 = LocalDate.of(2026, 9, 30);
    private static final LocalDate OCT_2 = LocalDate.of(2026, 10, 2);
    private static final LocalDate OCT_3 = LocalDate.of(2026, 10, 3);
    private static final LocalDate OCT_4 = LocalDate.of(2026, 10, 4);
    private static final LocalDate OCT_5 = LocalDate.of(2026, 10, 5);
    private static final LocalDate OCT_7 = LocalDate.of(2026, 10, 7);
    private static final LocalDate OCT_8 = LocalDate.of(2026, 10, 8);

    @Autowired RoomRepository rooms;
    @Autowired TestEntityManager entityManager;

    private TestData data;
    private Branch denton;
    private Branch fortWorth;
    private Room den101;
    private Room den102;
    private Room den103;
    private Guest guest;

    @BeforeEach
    void setUp() {
        data = new TestData(entityManager);
        denton = data.branch("DEN", "Denton Square", new BigDecimal("0.0825"));
        fortWorth = data.branch("FTW", "Fort Worth Stockyards", new BigDecimal("0.0825"));
        RoomType standard = data.roomType("STANDARD", 2);
        RoomType deluxe = data.roomType("DELUXE", 4);
        den101 = data.room(denton, standard, "101", "89.00");
        den102 = data.room(denton, standard, "102", "99.00");
        den103 = data.room(denton, deluxe, "103", "149.00");
        data.room(denton, standard, "104", "79.00", RoomStatus.OUT_OF_SERVICE);
        data.room(fortWorth, standard, "201", "79.00");
        guest = data.guest("ada@example.com");
    }

    @Test
    void returnsEveryInServiceRoomThatSleepsTheGuestsOrderedByBranchThenNumber() {
        data.flushAndClear();

        assertThat(numbers(available(null, null, null, 2, OCT_5, OCT_7)))
            .containsExactly("101", "102", "103", "201");
    }

    @Test
    void excludesARoomWithANightInsideTheRange() {
        data.booking(den101, guest, OCT_5, OCT_7, BookingStatus.CONFIRMED);
        data.flushAndClear();

        assertThat(numbers(available(null, null, null, 2, OCT_5, OCT_7)))
            .containsExactly("102", "103", "201");
    }

    @Test
    void includesARoomWhenTheStayEndsOnItsFirstBookedNight() {
        data.booking(den101, guest, OCT_7, OCT_8, BookingStatus.CONFIRMED);
        data.flushAndClear();

        assertThat(numbers(available(null, null, null, 2, OCT_5, OCT_7))).contains("101");
    }

    @Test
    void excludesOutOfServiceRooms() {
        data.flushAndClear();

        assertThat(numbers(available(denton.getId(), null, null, 2, OCT_5, OCT_7)))
            .doesNotContain("104");
    }

    @Test
    void excludesRoomsWhoseTypeSleepsFewerGuests() {
        data.flushAndClear();

        assertThat(numbers(available(null, null, null, 4, OCT_5, OCT_7))).containsExactly("103");
    }

    @Test
    void excludesARoomHeldByAnOverstayingGuestWhenTheStayStartsToday() {
        data.booking(den102, guest, SEP_28, SEP_30, BookingStatus.CHECKED_IN);
        data.flushAndClear();

        assertThat(numbers(available(denton.getId(), null, null, 2, TODAY, OCT_3)))
            .containsExactly("101", "103");
    }

    @Test
    void includesTheOverstayRoomWhenTheStayStartsAfterToday() {
        data.booking(den102, guest, SEP_28, SEP_30, BookingStatus.CHECKED_IN);
        data.flushAndClear();

        assertThat(numbers(available(denton.getId(), null, null, 2, OCT_2, OCT_4)))
            .containsExactly("101", "102", "103");
    }

    @Test
    void filtersByBranch() {
        data.flushAndClear();

        assertThat(numbers(available(fortWorth.getId(), null, null, 2, OCT_5, OCT_7)))
            .containsExactly("201");
    }

    @Test
    void filtersByRoomTypeCode() {
        data.flushAndClear();

        assertThat(numbers(available(null, "DELUXE", null, 2, OCT_5, OCT_7))).containsExactly("103");
    }

    @Test
    void filtersByMaximumNightlyRate() {
        data.flushAndClear();

        assertThat(numbers(available(null, null, new BigDecimal("89.00"), 2, OCT_5, OCT_7)))
            .containsExactly("101", "201");
    }

    @Test
    void findsTheRoomsOfABranchInNumberOrder() {
        data.flushAndClear();

        assertThat(numbers(rooms.findByBranchIdOrderByRoomNumber(denton.getId())))
            .containsExactly("101", "102", "103", "104");
    }

    @Test
    void findsARoomByBranchCodeAndNumber() {
        data.flushAndClear();

        assertThat(rooms.findByBranchCodeAndRoomNumber("DEN", "103"))
            .get()
            .extracting(Room::getId)
            .isEqualTo(den103.getId());
        assertThat(rooms.findByBranchCodeAndRoomNumber("FTW", "103")).isEmpty();
    }

    private List<Room> available(Long branchId, String typeCode, BigDecimal maxRate, int guests,
            LocalDate checkIn, LocalDate checkOut) {
        return rooms.findAvailable(branchId, typeCode, maxRate, guests, checkIn, checkOut, TODAY);
    }

    private List<String> numbers(List<Room> found) {
        return found.stream().map(Room::getRoomNumber).toList();
    }
}
