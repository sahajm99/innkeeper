package io.github.sahajm99.innkeeper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import java.math.BigDecimal;
import java.time.Instant;
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

import io.github.sahajm99.innkeeper.domain.BookingStatus;
import io.github.sahajm99.innkeeper.domain.CheckInOutPolicy.CheckOutOutcome;
import io.github.sahajm99.innkeeper.domain.IllegalBookingStateException;
import io.github.sahajm99.innkeeper.model.Booking;
import io.github.sahajm99.innkeeper.model.BookingEventType;
import io.github.sahajm99.innkeeper.model.Branch;
import io.github.sahajm99.innkeeper.model.Fine;
import io.github.sahajm99.innkeeper.model.InventoryItem;
import io.github.sahajm99.innkeeper.model.InvoiceStatus;
import io.github.sahajm99.innkeeper.model.ParkingKind;
import io.github.sahajm99.innkeeper.model.ParkingSpace;
import io.github.sahajm99.innkeeper.model.Payment;
import io.github.sahajm99.innkeeper.model.PaymentMethod;
import io.github.sahajm99.innkeeper.model.Priority;
import io.github.sahajm99.innkeeper.model.Room;
import io.github.sahajm99.innkeeper.repository.BookingRepository;
import io.github.sahajm99.innkeeper.seed.SeedData;
import io.github.sahajm99.innkeeper.service.StaffDeskService.Dashboard;
import io.github.sahajm99.innkeeper.service.StaffDeskService.Occupancy;
import io.github.sahajm99.innkeeper.support.AbstractServiceTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * The front desk: the two ledgers a shift starts from, and every transition a clerk can make on a
 * stay - checking in with a parking space, checking out early, late or on time, adding a fine and
 * taking a payment.
 *
 * <p>The dashboard tests seed the whole demo data set against the fixed clock, because what they
 * assert is the seed: four departures counting the overstay, three arrivals, Denton four rooms
 * full of eleven in service. Everything else builds the one branch and the two rooms it needs, so
 * the numbers in the assertions are the numbers in the fixture.</p>
 */
class StaffDeskServiceTest extends AbstractServiceTest {

    /** Branch today at the fixed clock of {@code ServiceTestBeans.NOW}. */
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 12);

    /** Ten in the morning in Chicago on the day after {@link #TODAY}. */
    private static final Instant TOMORROW_MORNING = Instant.parse("2026-09-13T15:00:00Z");

    @Autowired StaffDeskService desk;
    @Autowired BookingService bookings;
    @Autowired BookingRepository bookingRepository;

    /** One branch, two rooms so two stays can overlap, and one free guest space. */
    private record Desk(Long branchId, Long roomId, Long spareRoomId, Long spaceId) {
    }

    // --- the dashboard --------------------------------------------------------------------------

    @Test
    void theDashboardListsTodaysDeparturesArrivalsAndTheStateOfEveryBranch() {
        seedTheDemoData();

        Dashboard dashboard = desk.dashboard(null);

        assertThat(dashboard.today()).isEqualTo(TODAY);
        assertThat(dashboard.departures()).hasSize(4);
        assertThat(dashboard.departures()).extracting(Booking::getCheckOutDate)
            .as("the overstay is overdue, so it is listed first")
            .containsExactly(TODAY.minusDays(1), TODAY, TODAY, TODAY);
        assertThat(dashboard.departures().get(0).getRoom().getRoomNumber()).isEqualTo("301");
        assertThat(dashboard.departures()).extracting(Booking::getStatus)
            .containsOnly(BookingStatus.CHECKED_IN);

        assertThat(dashboard.arrivals()).hasSize(3);
        assertThat(dashboard.arrivals()).allSatisfy(arrival -> {
            assertThat(arrival.getCheckInDate()).isEqualTo(TODAY);
            assertThat(arrival.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        });

        assertThat(dashboard.occupancy()).extracting(occupancy -> occupancy.branch().getCode())
            .containsExactly("AUS", "DEN", "FTW");
        Occupancy denton = occupancyOf(dashboard, "DEN");
        assertThat(denton.occupied()).as("four Denton rooms are in use").isEqualTo(4);
        assertThat(denton.inService()).as("twelve rooms, with 106 out of service").isEqualTo(11);
        assertThat(denton.percent()).isEqualTo(36);

        assertThat(dashboard.openMaintenance())
            .containsExactly(entry(Priority.URGENT, 1L), entry(Priority.LOW, 1L));
        assertThat(dashboard.lowStock()).extracting(InventoryItem::getName)
            .containsExactly("Toilet paper", "Shampoo bottles");
        assertThat(dashboard.openComplaints()).isEqualTo(1);
    }

    @Test
    void theDashboardForOneBranchLeavesEveryOtherBranchOut() {
        seedTheDemoData();

        Dashboard dashboard = desk.dashboard(branchId("DEN"));

        assertThat(dashboard.departures()).hasSize(2);
        assertThat(dashboard.arrivals()).hasSize(1);
        assertThat(dashboard.occupancy()).hasSize(1);
        assertThat(dashboard.occupancy().get(0).branch().getCode()).isEqualTo("DEN");
        assertThat(dashboard.openMaintenance())
            .as("the Denton job in progress is no longer open")
            .containsExactly(entry(Priority.URGENT, 1L));
        assertThat(dashboard.lowStock()).extracting(InventoryItem::getName)
            .containsExactly("Toilet paper");
        assertThat(dashboard.openComplaints()).isEqualTo(1);
    }

    // --- checking in ----------------------------------------------------------------------------

    @Test
    void checkingInAnArrivalAssignsTheChosenSpaceAndRecordsTheEvent() {
        seedTheDemoData();
        Long denton = branchId("DEN");
        Booking arrival = bookingRepository.arrivals(denton, TODAY).get(0);
        ParkingSpace chosen = desk.freeGuestSpaces(denton).get(0);

        Booking checkedIn = desk.checkIn(arrival.getConfirmationCode(), chosen.getId(), "staff");

        assertThat(checkedIn.getStatus()).isEqualTo(BookingStatus.CHECKED_IN);
        assertThat(checkedIn.getCheckedInAt()).isEqualTo(clock.instant());
        assertThat(statusOf(arrival)).isEqualTo("CHECKED_IN");
        assertThat(bookingOnSpace(chosen.getId())).isEqualTo(arrival.getId());
        assertThat(desk.freeGuestSpaces(denton)).extracting(ParkingSpace::getId)
            .doesNotContain(chosen.getId());
        assertThat(eventsOf(arrival.getId(), BookingEventType.CHECKED_IN, "staff")).isEqualTo(1);
    }

    @Test
    void aStayThatStartsTomorrowCannotCheckInToday() {
        Desk house = aBranchWithTwoRoomsAndASpace();
        Booking tomorrow = stay(house.roomId(), TODAY.plusDays(1), TODAY.plusDays(3),
            BookingStatus.CONFIRMED);

        assertThatThrownBy(() -> desk.checkIn(tomorrow.getConfirmationCode(), null, "staff"))
            .isInstanceOf(IllegalBookingStateException.class)
            .hasMessageContaining("before the check-in date");

        assertThat(statusOf(tomorrow)).isEqualTo("CONFIRMED");
        assertThat(count("booking_event")).isZero();
    }

    @Test
    void aSpaceSomebodyElseTookRefusesTheCheckInAndLeavesTheGuestConfirmed() {
        Desk house = aBranchWithTwoRoomsAndASpace();
        // Both stays are built by one TestData, which is what keeps their two codes apart.
        List<Booking> stays = inTransaction(data -> List.of(
            data.booking(entityManager.find(Room.class, house.roomId()),
                data.guest("ada@example.com"), TODAY.minusDays(1), TODAY.plusDays(1),
                BookingStatus.CHECKED_IN),
            data.booking(entityManager.find(Room.class, house.spareRoomId()),
                data.guest("bo@example.com"), TODAY, TODAY.plusDays(2),
                BookingStatus.CONFIRMED)));
        Booking inHouse = stays.get(0);
        Booking arriving = stays.get(1);
        jdbc.update("update parking_space set booking_id = ? where id = ?",
            inHouse.getId(), house.spaceId());

        assertThatThrownBy(
            () -> desk.checkIn(arriving.getConfirmationCode(), house.spaceId(), "staff"))
            .isInstanceOf(ParkingUnavailableException.class);

        assertThat(statusOf(arriving)).isEqualTo("CONFIRMED");
        assertThat(bookingOnSpace(house.spaceId())).isEqualTo(inHouse.getId());
        assertThat(count("booking_event")).isZero();
    }

    // --- checking out ---------------------------------------------------------------------------

    @Test
    void checkingOutOnTimeChargesEveryNightReleasesTheSpaceAndLeavesTheInvoiceOpen() {
        Desk house = aBranchWithTwoRoomsAndASpace();
        Booking booking = stay(house.roomId(), TODAY.minusDays(2), TODAY, BookingStatus.CHECKED_IN);
        jdbc.update("update parking_space set booking_id = ? where id = ?",
            booking.getId(), house.spaceId());

        CheckOutOutcome outcome = desk.checkOut(booking.getConfirmationCode(), "staff");

        assertThat(outcome).isEqualTo(new CheckOutOutcome(2, 0, null));
        assertThat(bookingOnSpace(house.spaceId())).as("the space is free again").isNull();
        assertThat(nightsHeld(house.roomId())).as("nights already slept are kept").isEqualTo(2);

        BookingView view = bookings.view(booking);
        assertThat(view.status()).isEqualTo(BookingStatus.CHECKED_OUT);
        assertThat(view.checkedOutAt()).isEqualTo(clock.instant());
        assertThat(view.invoice().roomSubtotal()).isEqualByComparingTo("178.00");
        assertThat(view.invoice().tax()).isEqualByComparingTo("14.69");
        assertThat(view.invoice().total()).isEqualByComparingTo("192.69");
        assertThat(view.invoice().balance()).isEqualByComparingTo("192.69");
        assertThat(view.invoice().status()).isEqualTo(InvoiceStatus.OPEN);
        assertThat(view.events()).extracting(BookingView.EventView::type)
            .contains(BookingEventType.CHECKED_OUT);
    }

    @Test
    void anEarlyCheckOutReleasesTheNightsAfterItAndChargesOnlyTheNightsSlept() {
        Desk house = aBranchWithTwoRoomsAndASpace();
        Booking booking = stay(house.roomId(), TODAY, TODAY.plusDays(3), BookingStatus.CHECKED_IN);
        clock.set(TOMORROW_MORNING);

        CheckOutOutcome outcome = desk.checkOut(booking.getConfirmationCode(), "staff");

        assertThat(outcome.nightsCharged()).isEqualTo(1);
        assertThat(outcome.lateNights()).isZero();
        assertThat(outcome.releaseNightsFrom()).isEqualTo(TODAY.plusDays(1));
        assertThat(nightsHeld(house.roomId())).as("two nights go back on the market").isEqualTo(1);

        BookingView view = bookings.view(booking);
        assertThat(view.invoice().roomSubtotal()).isEqualByComparingTo("89.00");
        assertThat(view.invoice().total()).isEqualByComparingTo("96.34");
        assertThat(view.checkOut()).as("the booked dates never change")
            .isEqualTo(TODAY.plusDays(3));
    }

    /**
     * The seeded overstay: Nia Dummy in Denton 301 at 189.00 a night, booked out yesterday and
     * still in the room today. One night past check-out is one night of the rate as a fine.
     */
    @Test
    void aLateCheckOutOfTheOverstayChargesOneNightAsAFine() {
        seedTheDemoData();
        Booking overstay = bookingRepository.departures(null, TODAY).get(0);
        assertThat(overstay.getCheckOutDate()).isEqualTo(TODAY.minusDays(1));
        assertThat(overstay.getNightlyRate()).isEqualByComparingTo("189.00");

        CheckOutOutcome outcome = desk.checkOut(overstay.getConfirmationCode(), "staff");

        assertThat(outcome.lateNights()).isEqualTo(1);
        assertThat(outcome.nightsCharged()).as("the two booked nights, none released").isEqualTo(2);
        assertThat(outcome.releaseNightsFrom()).isNull();

        BookingView view = bookings.view(overstay);
        assertThat(view.status()).isEqualTo(BookingStatus.CHECKED_OUT);
        assertThat(view.fines()).singleElement().satisfies(fine -> {
            assertThat(fine.reason()).isEqualTo("Late check-out (1 night)");
            assertThat(fine.amount()).isEqualByComparingTo("189.00");
        });
        assertThat(view.invoice().fines()).isEqualByComparingTo("189.00");
        assertThat(view.invoice().roomSubtotal()).isEqualByComparingTo("378.00");
        assertThat(view.invoice().total()).isEqualByComparingTo("616.14");
    }

    @Test
    void twoCheckOutsAtOnceLeaveOneCheckedOutStayAndOneRefusal() throws Exception {
        Desk house = aBranchWithTwoRoomsAndASpace();
        Booking booking = stay(house.roomId(), TODAY.minusDays(2), TODAY, BookingStatus.CHECKED_IN);
        String code = booking.getConfirmationCode();
        CyclicBarrier startLine = new CyclicBarrier(2);
        List<Callable<CheckOutOutcome>> racers =
            List.of(checkOutAt(startLine, code), checkOutAt(startLine, code));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<CheckOutOutcome> succeeded = new ArrayList<>();
        List<Throwable> refused = new ArrayList<>();
        try {
            for (Future<CheckOutOutcome> attempt : pool.invokeAll(racers, 60, TimeUnit.SECONDS)) {
                try {
                    succeeded.add(attempt.get());
                } catch (ExecutionException failure) {
                    refused.add(failure.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(succeeded).hasSize(1);
        assertThat(refused).hasSize(1).allSatisfy(failure -> assertThat(failure)
            .isInstanceOfAny(ObjectOptimisticLockingFailureException.class,
                IllegalBookingStateException.class));
        assertThat(statusOf(booking)).isEqualTo("CHECKED_OUT");
        assertThat(eventsOf(booking.getId(), BookingEventType.CHECKED_OUT, "staff")).isEqualTo(1);
        assertThat(count("invoice")).isEqualTo(1);
    }

    // --- fines and payments ---------------------------------------------------------------------

    @Test
    void aFineOnAnInHouseGuestGoesStraightOntoTheInvoice() {
        Desk house = aBranchWithTwoRoomsAndASpace();
        Booking booking = stay(house.roomId(), TODAY.minusDays(1), TODAY.plusDays(1),
            BookingStatus.CHECKED_IN);

        Fine fine = desk.addFine(booking.getConfirmationCode(), "Smoking in room",
            new BigDecimal("40.00"), "staff");

        assertThat(fine.getAmount()).isEqualByComparingTo("40.00");
        assertThat(fine.getIssuedAt()).isEqualTo(clock.instant());

        BookingView view = bookings.view(booking);
        assertThat(view.invoice().fines()).isEqualByComparingTo("40.00");
        assertThat(view.invoice().roomSubtotal()).isEqualByComparingTo("178.00");
        assertThat(view.invoice().total()).as("fines are untaxed").isEqualByComparingTo("232.69");
        assertThat(view.events()).extracting(BookingView.EventView::type)
            .contains(BookingEventType.FINE_ADDED);
    }

    @Test
    void aFineCannotBeAddedOnceTheGuestHasCheckedOut() {
        Desk house = aBranchWithTwoRoomsAndASpace();
        Booking gone = stay(house.roomId(), TODAY.minusDays(3), TODAY.minusDays(1),
            BookingStatus.CHECKED_OUT);

        assertThatThrownBy(() -> desk.addFine(gone.getConfirmationCode(), "Lost key card",
            new BigDecimal("25.00"), "staff"))
            .isInstanceOf(IllegalBookingStateException.class);

        assertThat(count("fine")).isZero();
    }

    @Test
    void aFineHasToBeMoreThanNothing() {
        Desk house = aBranchWithTwoRoomsAndASpace();
        Booking booking = stay(house.roomId(), TODAY.minusDays(1), TODAY.plusDays(1),
            BookingStatus.CHECKED_IN);

        assertThatThrownBy(() -> desk.addFine(booking.getConfirmationCode(), "Nothing at all",
            new BigDecimal("0.00"), "staff"))
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(count("fine")).isZero();
    }

    @Test
    void aPaymentBeforeCheckOutIsRefused() {
        Desk house = aBranchWithTwoRoomsAndASpace();
        Booking booking = stay(house.roomId(), TODAY.minusDays(1), TODAY.plusDays(1),
            BookingStatus.CHECKED_IN);

        assertThatThrownBy(() -> desk.recordPayment(booking.getConfirmationCode(),
            new BigDecimal("10.00"), PaymentMethod.CARD, "DEMO-CARD", "staff"))
            .isInstanceOf(IllegalBookingStateException.class);

        assertThat(count("payment")).isZero();
    }

    @Test
    void aPaymentLargerThanTheBalanceIsRefused() {
        Booking booking = aCheckedOutStay();
        BigDecimal balance = bookings.view(booking).invoice().balance();

        assertThatThrownBy(() -> desk.recordPayment(booking.getConfirmationCode(),
            balance.add(new BigDecimal("0.01")), PaymentMethod.CARD, "DEMO-CARD", "staff"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("balance");

        assertThat(count("payment")).isZero();
        assertThat(bookings.view(booking).invoice().status()).isEqualTo(InvoiceStatus.OPEN);
    }

    @Test
    void aPaymentForTheWholeBalanceSettlesTheInvoice() {
        Booking booking = aCheckedOutStay();
        BigDecimal balance = bookings.view(booking).invoice().balance();

        Payment payment = desk.recordPayment(booking.getConfirmationCode(), balance,
            PaymentMethod.CARD, "DEMO-CARD", "staff");

        assertThat(payment.getAmount()).isEqualByComparingTo(balance);
        assertThat(payment.getPaidAt()).isEqualTo(clock.instant());

        BookingView view = bookings.view(booking);
        assertThat(view.invoice().status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(view.invoice().balance()).isEqualByComparingTo("0.00");
        assertThat(view.payments()).singleElement()
            .satisfies(paid -> assertThat(paid.method()).isEqualTo(PaymentMethod.CARD));
        assertThat(view.events()).extracting(BookingView.EventView::type)
            .contains(BookingEventType.PAYMENT_RECORDED);
    }

    // --- looking things up ----------------------------------------------------------------------

    @Test
    void searchFindsAStayByItsCodeItsEmailOrItsGuestName() {
        Desk house = aBranchWithTwoRoomsAndASpace();
        Booking booking = stay(house.roomId(), TODAY, TODAY.plusDays(2), BookingStatus.CONFIRMED);

        assertThat(desk.search(booking.getConfirmationCode())).extracting(Booking::getId)
            .containsExactly(booking.getId());
        assertThat(desk.search("ADA@example")).hasSize(1);
        assertThat(desk.search("lovelace")).hasSize(1);
        assertThat(desk.search("nobody at all")).isEmpty();
        assertThat(desk.search("   ")).as("an empty box is not a search for everything").isEmpty();
        assertThat(desk.search("lovelace").get(0).getRoom().getRoomNumber())
            .as("the room survives the trip out of the session").isEqualTo("101");
    }

    @Test
    void freeGuestSpacesLeavesOutStaffSpacesAndSpacesAlreadyTaken() {
        Desk house = aBranchWithTwoRoomsAndASpace();
        Booking booking = stay(house.roomId(), TODAY.minusDays(1), TODAY.plusDays(1),
            BookingStatus.CHECKED_IN);
        inTransaction(data -> {
            Branch branch = entityManager.find(Branch.class, house.branchId());
            data.parkingSpace(branch, "P2", ParkingKind.GUEST);
            data.parkingSpace(branch, "S1", ParkingKind.STAFF);
            return null;
        });
        jdbc.update("update parking_space set booking_id = ? where id = ?",
            booking.getId(), house.spaceId());

        assertThat(desk.freeGuestSpaces(house.branchId()))
            .extracting(ParkingSpace::getSpaceNumber)
            .containsExactly("P2");
    }

    @Test
    void theDeskScopesToTheEmployeesBranchForStaffAndToEveryBranchForAManager() {
        seedTheDemoData();

        assertThat(desk.branchForUsername("staff")).isEqualTo(branchId("DEN"));
        assertThat(desk.branchForUsername("manager")).as("managers see every branch").isNull();
        assertThat(desk.branchForUsername("guest")).isNull();
        assertThat(desk.branchForUsername("nobody")).isNull();
    }

    // --- fixtures -------------------------------------------------------------------------------

    private void seedTheDemoData() {
        new SeedData(jdbc, clock).seed();
    }

    private Desk aBranchWithTwoRoomsAndASpace() {
        return inTransaction(data -> {
            Branch denton = data.branch("DEN");
            Room room = data.room(denton, data.roomType("STANDARD", 2), "101", "89.00");
            Room spare = data.room(denton, data.roomType("DELUXE", 3), "102", "129.00");
            return new Desk(denton.getId(), room.getId(), spare.getId(),
                data.parkingSpace(denton, "P1", ParkingKind.GUEST).getId());
        });
    }

    private Booking stay(Long roomId, LocalDate checkIn, LocalDate checkOut, BookingStatus status) {
        return inTransaction(data -> data.booking(entityManager.find(Room.class, roomId),
            data.guest("ada@example.com"), checkIn, checkOut, status));
    }

    /** A two night stay checked out through the desk, so it has a real invoice to pay. */
    private Booking aCheckedOutStay() {
        Desk house = aBranchWithTwoRoomsAndASpace();
        Booking booking = stay(house.roomId(), TODAY.minusDays(2), TODAY, BookingStatus.CHECKED_IN);
        desk.checkOut(booking.getConfirmationCode(), "staff");
        return booking;
    }

    private Callable<CheckOutOutcome> checkOutAt(CyclicBarrier startLine, String code) {
        return () -> {
            startLine.await(30, TimeUnit.SECONDS);
            return desk.checkOut(code, "staff");
        };
    }

    private Occupancy occupancyOf(Dashboard dashboard, String branchCode) {
        return dashboard.occupancy().stream()
            .filter(occupancy -> occupancy.branch().getCode().equals(branchCode))
            .findFirst()
            .orElseThrow();
    }

    private Long branchId(String code) {
        return jdbc.queryForObject("select id from branch where code = ?", Long.class, code);
    }

    private String statusOf(Booking booking) {
        return jdbc.queryForObject("select status from booking where id = ?", String.class,
            booking.getId());
    }

    private Long bookingOnSpace(Long spaceId) {
        return jdbc.queryForObject("select booking_id from parking_space where id = ?", Long.class,
            spaceId);
    }

    private int eventsOf(Long bookingId, BookingEventType type, String actor) {
        Integer rows = jdbc.queryForObject("select count(*) from booking_event "
            + "where booking_id = ? and event_type = ? and actor = ?",
            Integer.class, bookingId, type.name(), actor);
        return rows == null ? 0 : rows;
    }
}
