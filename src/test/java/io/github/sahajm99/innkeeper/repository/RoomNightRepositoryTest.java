package io.github.sahajm99.innkeeper.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import io.github.sahajm99.innkeeper.domain.BookingStatus;
import io.github.sahajm99.innkeeper.model.Booking;
import io.github.sahajm99.innkeeper.model.Branch;
import io.github.sahajm99.innkeeper.model.Guest;
import io.github.sahajm99.innkeeper.model.Room;
import io.github.sahajm99.innkeeper.model.RoomNight;
import io.github.sahajm99.innkeeper.model.RoomType;
import io.github.sahajm99.innkeeper.support.TestData;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class RoomNightRepositoryTest {

    private static final LocalDate OCT_3 = LocalDate.of(2026, 10, 3);
    private static final LocalDate OCT_4 = LocalDate.of(2026, 10, 4);
    private static final LocalDate OCT_5 = LocalDate.of(2026, 10, 5);
    private static final LocalDate OCT_6 = LocalDate.of(2026, 10, 6);
    private static final LocalDate OCT_7 = LocalDate.of(2026, 10, 7);
    private static final LocalDate OCT_8 = LocalDate.of(2026, 10, 8);

    @Autowired RoomNightRepository roomNights;
    @Autowired TestEntityManager entityManager;

    private TestData data;
    private Room room101;
    private Room room102;
    private Guest guest;

    @BeforeEach
    void setUp() {
        data = new TestData(entityManager);
        Branch branch = data.branch("DEN");
        RoomType standard = data.roomType("STANDARD", 2);
        room101 = data.room(branch, standard, "101", "89.00");
        room102 = data.room(branch, standard, "102", "99.00");
        guest = data.guest("ada@example.com");
    }

    @Test
    void theUniqueConstraintRejectsASecondBookingForTheSameRoomAndNight() {
        data.booking(room101, guest, OCT_5, OCT_6, BookingStatus.CONFIRMED);
        Booking other = data.booking(room102, guest, OCT_5, OCT_6, BookingStatus.CONFIRMED);
        data.flush();

        RoomNight clash = new RoomNight();
        clash.setRoom(room101);
        clash.setNightDate(OCT_5);
        clash.setBooking(other);

        assertThatThrownBy(() -> roomNights.saveAndFlush(clash))
            .isInstanceOf(DataIntegrityViolationException.class)
            .satisfies(failure -> assertThat(TestData.messageChain(failure))
                .containsIgnoringCase("uq_room_night"));
    }

    @Test
    void bookedNightsReturnsOnlyTheNightsInsideTheHalfOpenRange() {
        data.booking(room101, guest, OCT_3, OCT_8, BookingStatus.CONFIRMED);
        data.booking(room102, guest, OCT_3, OCT_8, BookingStatus.CONFIRMED);
        data.flushAndClear();

        List<LocalDate> nights = roomNights.bookedNights(room101.getId(), OCT_4, OCT_7);

        assertThat(nights).containsExactlyInAnyOrder(OCT_4, OCT_5, OCT_6);
    }

    @Test
    void bookedNightsIgnoresOtherRooms() {
        data.booking(room102, guest, OCT_3, OCT_8, BookingStatus.CONFIRMED);
        data.flushAndClear();

        assertThat(roomNights.bookedNights(room101.getId(), OCT_3, OCT_8)).isEmpty();
    }

    @Test
    void deletingFromANightKeepsTheEarlierNightsOfTheSameBooking() {
        Booking booking = data.booking(room101, guest, OCT_3, OCT_8, BookingStatus.CONFIRMED);
        data.flush();

        long removed = roomNights.deleteByBookingIdAndNightDateGreaterThanEqual(booking.getId(), OCT_6);
        data.flushAndClear();

        assertThat(removed).isEqualTo(2);
        assertThat(roomNights.bookedNights(room101.getId(), OCT_3, OCT_8))
            .containsExactlyInAnyOrder(OCT_3, OCT_4, OCT_5);
    }

    @Test
    void deletingByBookingReleasesEveryNightOfThatBookingOnly() {
        Booking booking = data.booking(room101, guest, OCT_3, OCT_5, BookingStatus.CONFIRMED);
        data.booking(room102, guest, OCT_3, OCT_5, BookingStatus.CONFIRMED);
        data.flush();

        long removed = roomNights.deleteByBookingId(booking.getId());
        data.flushAndClear();

        assertThat(removed).isEqualTo(2);
        assertThat(roomNights.bookedNights(room101.getId(), OCT_3, OCT_8)).isEmpty();
        assertThat(roomNights.bookedNights(room102.getId(), OCT_3, OCT_8))
            .containsExactlyInAnyOrder(OCT_3, OCT_4);
    }
}
