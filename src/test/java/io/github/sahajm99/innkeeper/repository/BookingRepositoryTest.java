package io.github.sahajm99.innkeeper.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import io.github.sahajm99.innkeeper.domain.BookingStatus;
import io.github.sahajm99.innkeeper.model.Booking;
import io.github.sahajm99.innkeeper.model.Branch;
import io.github.sahajm99.innkeeper.model.Guest;
import io.github.sahajm99.innkeeper.model.Room;
import io.github.sahajm99.innkeeper.model.RoomType;
import io.github.sahajm99.innkeeper.support.TestData;
import io.github.sahajm99.innkeeper.support.UnseededDatabase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@UnseededDatabase
class BookingRepositoryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    private static final LocalDate SEP_25 = LocalDate.of(2026, 9, 25);
    private static final LocalDate SEP_28 = LocalDate.of(2026, 9, 28);
    private static final LocalDate SEP_30 = LocalDate.of(2026, 9, 30);
    private static final LocalDate OCT_2 = LocalDate.of(2026, 10, 2);
    private static final LocalDate OCT_3 = LocalDate.of(2026, 10, 3);
    private static final LocalDate OCT_4 = LocalDate.of(2026, 10, 4);
    private static final LocalDate OCT_10 = LocalDate.of(2026, 10, 10);
    private static final LocalDate OCT_12 = LocalDate.of(2026, 10, 12);

    @Autowired BookingRepository bookings;
    @Autowired GuestRepository guests;
    @Autowired TestEntityManager entityManager;
    @Autowired JdbcTemplate jdbc;

    private TestData data;
    private Branch denton;
    private Branch fortWorth;
    private Booking arrivalDenton;
    private Booking arrivalFortWorth;
    private Booking laterArrival;
    private Booking cancelledToday;
    private Booking departureToday;
    private Booking overstay;
    private Booking futureStay;

    @BeforeEach
    void setUp() {
        data = new TestData(entityManager);
        denton = data.branch("DEN", "Denton Square", new BigDecimal("0.0825"));
        fortWorth = data.branch("FTW", "Fort Worth Stockyards", new BigDecimal("0.0825"));
        RoomType standard = data.roomType("STANDARD", 2);
        Room den101 = data.room(denton, standard, "101", "89.00");
        Room den102 = data.room(denton, standard, "102", "99.00");
        Room den103 = data.room(denton, standard, "103", "109.00");
        Room den104 = data.room(denton, standard, "104", "79.00");
        Room ftw201 = data.room(fortWorth, standard, "201", "79.00");
        Guest ada = data.guest("Ada", "Lovelace", "ada@example.com");
        Guest bob = data.guest("Bob", "Marks", "bob@example.com");
        Guest cara = data.guest("Cara", "Nimo", "cara@example.com");

        arrivalDenton = data.booking(den101, ada, TODAY, OCT_3, BookingStatus.CONFIRMED);
        arrivalFortWorth = data.booking(ftw201, bob, TODAY, OCT_4, BookingStatus.CONFIRMED);
        futureStay = data.booking(ftw201, ada, OCT_10, OCT_12, BookingStatus.CONFIRMED);
        laterArrival = data.booking(den102, bob, OCT_2, OCT_4, BookingStatus.CONFIRMED);
        cancelledToday = data.booking(den104, cara, TODAY, OCT_2, BookingStatus.CANCELLED);
        departureToday = data.booking(den103, cara, SEP_28, TODAY, BookingStatus.CHECKED_IN);
        overstay = data.booking(den104, cara, SEP_25, SEP_30, BookingStatus.CHECKED_IN);
        data.flushAndClear();
    }

    @Test
    void findsABookingByItsConfirmationCode() {
        assertThat(bookings.findByConfirmationCode(arrivalDenton.getConfirmationCode()))
            .get()
            .extracting(Booking::getId)
            .isEqualTo(arrivalDenton.getId());
        assertThat(bookings.findByConfirmationCode("INN-ZZZZZZ")).isEmpty();
    }

    @Test
    void findByGuestEmailIgnoresCaseAndReturnsTheNewestStayFirst() {
        List<Booking> found = bookings.findByGuestEmail("ADA@Example.COM");

        assertThat(codes(found))
            .containsExactly(futureStay.getConfirmationCode(), arrivalDenton.getConfirmationCode());
    }

    @Test
    void arrivalsAreConfirmedBookingsCheckingInOnTheDate() {
        List<Booking> found = bookings.arrivals(null, TODAY);

        assertThat(codes(found)).containsExactly(
            arrivalDenton.getConfirmationCode(), arrivalFortWorth.getConfirmationCode());
        assertThat(codes(found)).doesNotContain(
            laterArrival.getConfirmationCode(),
            cancelledToday.getConfirmationCode(),
            departureToday.getConfirmationCode());
    }

    @Test
    void arrivalsCanBeNarrowedToOneBranch() {
        assertThat(codes(bookings.arrivals(denton.getId(), TODAY)))
            .containsExactly(arrivalDenton.getConfirmationCode());
        assertThat(codes(bookings.arrivals(fortWorth.getId(), TODAY)))
            .containsExactly(arrivalFortWorth.getConfirmationCode());
    }

    @Test
    void departuresAreCheckedInBookingsDueOnOrBeforeTheDateIncludingOverstays() {
        List<Booking> found = bookings.departures(null, TODAY);

        assertThat(codes(found))
            .containsExactly(overstay.getConfirmationCode(), departureToday.getConfirmationCode());
    }

    @Test
    void departuresCanBeNarrowedToOneBranch() {
        assertThat(bookings.departures(fortWorth.getId(), TODAY)).isEmpty();
        assertThat(bookings.departures(denton.getId(), TODAY)).hasSize(2);
    }

    @Test
    void occupiedRoomsCountsCheckedInBookingsInTheBranch() {
        assertThat(bookings.occupiedRooms(denton.getId())).isEqualTo(2);
        assertThat(bookings.occupiedRooms(fortWorth.getId())).isZero();
    }

    @Test
    void searchMatchesConfirmationCodeEmailOrGuestName() {
        assertThat(codes(bookings.search(arrivalDenton.getConfirmationCode().toLowerCase(Locale.ROOT))))
            .containsExactly(arrivalDenton.getConfirmationCode());
        assertThat(codes(bookings.search("BOB@EXAMPLE")))
            .containsExactly(laterArrival.getConfirmationCode(), arrivalFortWorth.getConfirmationCode());
        assertThat(codes(bookings.search("ada love")))
            .containsExactly(futureStay.getConfirmationCode(), arrivalDenton.getConfirmationCode());
        assertThat(bookings.search("no-such-guest")).isEmpty();
    }

    @Test
    void findsEveryBookingCheckingInOnADateWhateverItsStatus() {
        assertThat(codes(bookings.findByCheckInDateOrderByCheckInDate(TODAY)))
            .containsExactlyInAnyOrder(
                arrivalDenton.getConfirmationCode(),
                arrivalFortWorth.getConfirmationCode(),
                cancelledToday.getConfirmationCode());
    }

    @Test
    void guestEmailsAreTrimmedAndStoredLowerCase() {
        Guest mixed = data.guest("Grace", "Hopper", "  GRACE.Hopper@Example.COM ");
        data.flushAndClear();

        assertThat(guests.findById(mixed.getId()))
            .get()
            .extracting(Guest::getEmail)
            .isEqualTo("grace.hopper@example.com");
    }

    @Test
    void theDatabaseRefusesAGuestEmailThatIsNotLowerCase() {
        assertThatThrownBy(() -> jdbc.update(
                "insert into guest (first_name, last_name, email) values (?, ?, ?)",
                "Mixed", "Case", "Mixed@Example.com"))
            .isInstanceOf(DataIntegrityViolationException.class)
            .satisfies(failure -> assertThat(TestData.messageChain(failure))
                .containsIgnoringCase("ck_guest_email_lower"));
    }

    private List<String> codes(List<Booking> found) {
        return found.stream().map(Booking::getConfirmationCode).toList();
    }
}
