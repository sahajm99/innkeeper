package io.github.sahajm99.innkeeper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import io.github.sahajm99.innkeeper.domain.BookingRuleException;
import io.github.sahajm99.innkeeper.domain.BookingStatus;
import io.github.sahajm99.innkeeper.domain.ConfirmationCodes;
import io.github.sahajm99.innkeeper.domain.IllegalBookingStateException;
import io.github.sahajm99.innkeeper.domain.InvoiceLineKind;
import io.github.sahajm99.innkeeper.domain.NotFoundException;
import io.github.sahajm99.innkeeper.domain.RoomUnavailableException;
import io.github.sahajm99.innkeeper.model.Booking;
import io.github.sahajm99.innkeeper.model.BookingEventType;
import io.github.sahajm99.innkeeper.model.Branch;
import io.github.sahajm99.innkeeper.model.Guest;
import io.github.sahajm99.innkeeper.model.InvoiceStatus;
import io.github.sahajm99.innkeeper.model.Room;
import io.github.sahajm99.innkeeper.model.RoomStatus;
import io.github.sahajm99.innkeeper.model.RoomType;
import io.github.sahajm99.innkeeper.service.BookingView.EventView;
import io.github.sahajm99.innkeeper.service.BookingView.LineView;
import io.github.sahajm99.innkeeper.support.AbstractServiceTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The booking lifecycle the guest pages and the API sit on: what creating a booking writes, what
 * it refuses, and what cancelling costs on either side of the deadline.
 *
 * <p>The clock is fixed at 12 September 2026, so every date here is literal rather than
 * relative.</p>
 */
class BookingServiceTest extends AbstractServiceTest {

    private static final LocalDate OCT_1 = LocalDate.of(2026, 10, 1);
    private static final LocalDate OCT_3 = LocalDate.of(2026, 10, 3);
    private static final LocalDate OCT_4 = LocalDate.of(2026, 10, 4);
    private static final LocalDate OCT_5 = LocalDate.of(2026, 10, 5);
    private static final LocalDate OCT_6 = LocalDate.of(2026, 10, 6);
    private static final LocalDate OCT_10 = LocalDate.of(2026, 10, 10);
    private static final LocalDate OCT_12 = LocalDate.of(2026, 10, 12);

    /** Noon on the check-in date in Chicago, which is well past the 48 hour free window. */
    private static final Instant CHECK_IN_DAY_NOON = Instant.parse("2026-10-03T17:00:00Z");

    /** Mid-morning on 3 October in Chicago, so branch today is the 3rd. */
    private static final Instant OCT_3_MORNING = Instant.parse("2026-10-03T14:00:00Z");

    @Autowired BookingService bookings;
    @Autowired Random confirmationCodeRandom;
    @Autowired PlatformTransactionManager transactionManager;

    private Long roomId;
    private Long spareRoomId;
    private Long closedRoomId;

    @BeforeEach
    void createTheBranchAndItsRooms() {
        inTransaction(data -> {
            Branch denton = data.branch("DEN");
            RoomType standard = data.roomType("STANDARD", 2);
            roomId = data.room(denton, standard, "101", "89.00").getId();
            spareRoomId = data.room(denton, standard, "102", "99.00").getId();
            closedRoomId = data.room(denton, standard, "199", "89.00", RoomStatus.OUT_OF_SERVICE)
                .getId();
            return null;
        });
    }

    // --- creating -----------------------------------------------------------------------------

    @Test
    void creatingABookingWritesTheNightsTheInvoiceAndTheCreatedEvent() {
        Booking booking = bookings.create(command(roomId, OCT_1, OCT_4));

        BookingView view = bookings.view(booking);
        assertThat(view.code()).matches(ConfirmationCodes.PATTERN.pattern());
        assertThat(view.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(view.nights()).isEqualTo(3);
        assertThat(view.roomNumber()).isEqualTo("101");
        assertThat(view.nightlyRate()).isEqualByComparingTo("89.00");
        assertThat(nightsHeld(roomId)).isEqualTo(3);

        assertThat(view.invoice().roomSubtotal()).isEqualByComparingTo("267.00");
        assertThat(view.invoice().tax()).isEqualByComparingTo("22.03");
        assertThat(view.invoice().total()).isEqualByComparingTo("289.03");
        assertThat(view.invoice().balance()).isEqualByComparingTo("289.03");
        assertThat(view.invoice().status()).isEqualTo(InvoiceStatus.OPEN);
        assertThat(view.lines()).extracting(LineView::kind)
            .containsExactly(InvoiceLineKind.ROOM_NIGHTS, InvoiceLineKind.TAX);

        assertThat(view.events()).extracting(EventView::type)
            .containsExactly(BookingEventType.CREATED);
        assertThat(view.events()).extracting(EventView::actor).containsExactly("guest");
    }

    @Test
    void theGuestEmailIsStoredLowerCased() {
        bookings.create(command(roomId, OCT_1, OCT_4));

        assertThat(jdbc.queryForObject("select email from guest", String.class))
            .isEqualTo("ada@example.com");
    }

    @Test
    void aSecondBookingOverlappingTheFirstIsRefusedAndLeavesNoRowsBehind() {
        bookings.create(command(roomId, OCT_1, OCT_4));
        int guests = count("guest");
        int booked = count("booking");
        int nights = count("room_night");

        assertThatThrownBy(() -> bookings.create(command(roomId, OCT_3, OCT_6)))
            .isInstanceOf(RoomUnavailableException.class)
            .hasMessageContaining("101");

        assertThat(count("guest")).isEqualTo(guests);
        assertThat(count("booking")).isEqualTo(booked);
        assertThat(count("room_night")).isEqualTo(nights);
    }

    /**
     * Rule 9: a guest who has not checked out keeps the room, so it cannot be sold from today even
     * though the nights it booked have run out. Nights from after the overstay are still sellable.
     */
    @Test
    void aRoomStillHeldByAnOverstayingGuestCannotBeBookedFromToday() {
        inTransaction(data -> data.booking(entityManager.find(Room.class, roomId),
            data.guest("overstay@example.com"), OCT_1, OCT_3, BookingStatus.CHECKED_IN));
        clock.set(OCT_3_MORNING);

        assertThatThrownBy(() -> bookings.create(command(roomId, OCT_3, OCT_4)))
            .isInstanceOf(RoomUnavailableException.class)
            .hasMessageContaining("101");
        assertThat(count("booking")).isEqualTo(1);

        assertThat(bookings.create(command(roomId, OCT_5, OCT_6)).getConfirmationCode())
            .matches(ConfirmationCodes.PATTERN.pattern());
    }

    /**
     * A refusal must not poison a transaction the caller already had open. The unit of work runs in
     * its own transaction, so the loser's rollback is the loser's alone: with the template on plain
     * REQUIRED propagation the caller's commit would fail with UnexpectedRollbackException instead.
     */
    @Test
    void aRefusalDoesNotPoisonATransactionTheCallerAlreadyOpened() {
        bookings.create(command(roomId, OCT_1, OCT_4));
        int booked = count("booking");
        int guests = count("guest");
        int nights = count("room_night");
        AtomicReference<RuntimeException> refusal = new AtomicReference<>();

        String outcome = new TransactionTemplate(transactionManager).execute(status -> {
            try {
                bookings.create(command(roomId, OCT_3, OCT_6));
                return "booked";
            } catch (RuntimeException refused) {
                refusal.set(refused);
                return "refused";
            }
        });

        assertThat(outcome).isEqualTo("refused");
        assertThat(refusal.get()).isInstanceOf(RoomUnavailableException.class);
        assertThat(count("booking")).isEqualTo(booked);
        assertThat(count("guest")).isEqualTo(guests);
        assertThat(count("room_night")).isEqualTo(nights);
    }

    @Test
    void backToBackBookingsForTheSameRoomBothSucceed() {
        Booking first = bookings.create(command(roomId, OCT_1, OCT_4));
        Booking second = bookings.create(command(roomId, OCT_4, OCT_6));

        assertThat(second.getConfirmationCode()).isNotEqualTo(first.getConfirmationCode());
        assertThat(nightsHeld(roomId)).isEqualTo(5);
        assertThat(count("booking")).isEqualTo(2);
    }

    @Test
    void anOutOfServiceRoomIsNeverBookable() {
        assertThatThrownBy(() -> bookings.create(command(closedRoomId, OCT_1, OCT_4)))
            .isInstanceOf(RoomUnavailableException.class)
            .hasMessageContaining("out of service");

        assertThat(count("booking")).isZero();
    }

    @Test
    void tooManyGuestsForTheRoomTypeIsARuleFailure() {
        BookingRuleException failure = catchThrowableOfType(BookingRuleException.class,
            () -> bookings.create(new CreateBookingCommand(roomId, OCT_1, OCT_4, 2, 2,
                "Ada", "Lovelace", "ada@example.com", null, null, "guest")));

        assertThat(failure).hasMessageContaining("sleeps up to 2 guests");
        assertThat(failure.field()).isEqualTo("children");
        assertThat(count("booking")).isZero();
        assertThat(count("guest")).isZero();
    }

    @Test
    void aConfirmationCodeCollisionIsRetriedUntilAFreeCodeComesUp() {
        long seed = 20260912L;
        Random probe = new Random(seed);
        String taken = ConfirmationCodes.generate(probe);
        String next = ConfirmationCodes.generate(probe);
        inTransaction(data -> {
            Room spare = entityManager.find(Room.class, spareRoomId);
            Guest guest = data.guest("held@example.com");
            data.booking(spare, guest, OCT_10, OCT_12, BookingStatus.CONFIRMED)
                .setConfirmationCode(taken);
            return null;
        });

        confirmationCodeRandom.setSeed(seed);
        Booking booking = bookings.create(command(roomId, OCT_1, OCT_4));

        assertThat(booking.getConfirmationCode()).isEqualTo(next).isNotEqualTo(taken);
        assertThat(jdbc.queryForObject("select count(*) from booking where confirmation_code = ?",
            Integer.class, taken)).isEqualTo(1);
    }

    // --- looking up ---------------------------------------------------------------------------

    @Test
    void requireByCodeReturnsTheBookingOrSaysItIsNotThere() {
        Booking booking = bookings.create(command(roomId, OCT_1, OCT_4));

        assertThat(bookings.requireByCode(booking.getConfirmationCode()).getId())
            .isEqualTo(booking.getId());
        assertThatThrownBy(() -> bookings.requireByCode("INN-ZZZZZZ"))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void findByCodeAndEmailNeedsBothAndIsEmptyForTheWrongEmail() {
        Booking booking = bookings.create(command(roomId, OCT_1, OCT_4));
        String code = booking.getConfirmationCode();

        assertThat(bookings.findByCodeAndEmail(code, "ADA@example.COM")).isPresent();
        assertThat(bookings.findByCodeAndEmail(code, "someone.else@example.com")).isEmpty();
        assertThat(bookings.findByCodeAndEmail(code, "")).isEmpty();
        assertThat(bookings.findByCodeAndEmail("INN-ZZZZZZ", "ada@example.com")).isEmpty();
    }

    /** Most seeded bookings have no invoice, so the booking page has to survive one that does not. */
    @Test
    void aBookingWithNoInvoiceYetStillHasAView() {
        Booking plain = inTransaction(data -> data.booking(
            entityManager.find(Room.class, spareRoomId), data.guest("ada@example.com"),
            OCT_10, OCT_12, BookingStatus.CONFIRMED));

        BookingView view = bookings.view(plain);

        assertThat(view.invoice()).isNull();
        assertThat(view.lines()).isEmpty();
        assertThat(view.payments()).isEmpty();
        assertThat(view.nights()).isEqualTo(2);
        assertThat(view.roomNumber()).isEqualTo("102");
    }

    @Test
    void theCancellationDeadlineAndTheFeeRightNowAreBothExposed() {
        Booking booking = bookings.create(command(roomId, OCT_1, OCT_4));

        assertThat(bookings.cancellationDeadline(booking))
            .isEqualTo(Instant.parse("2026-09-29T20:00:00Z"));
        assertThat(bookings.cancellationFeeNow(booking)).isEqualByComparingTo("0.00");

        clock.set(Instant.parse("2026-09-30T20:00:00Z"));
        assertThat(bookings.cancellationFeeNow(booking)).isEqualByComparingTo("89.00");
    }

    // --- cancelling ---------------------------------------------------------------------------

    @Test
    void cancellingBeforeTheDeadlineIsFreeReleasesTheNightsAndVoidsTheInvoice() {
        Booking booking = bookings.create(command(roomId, OCT_1, OCT_4));

        Booking cancelled = bookings.cancel(booking.getConfirmationCode(), "guest");

        assertThat(cancelled.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(cancelled.getCancellationFee()).isEqualByComparingTo("0.00");
        assertThat(cancelled.getCancelledAt()).isEqualTo(clock.instant());
        assertThat(nightsHeld(roomId)).isZero();

        BookingView view = bookings.view(cancelled);
        assertThat(view.invoice().total()).isEqualByComparingTo("0.00");
        assertThat(view.invoice().status()).isEqualTo(InvoiceStatus.VOID);
        assertThat(view.lines()).isEmpty();
        assertThat(view.events()).extracting(EventView::type)
            .containsExactly(BookingEventType.CREATED, BookingEventType.CANCELLED);
    }

    @Test
    void cancellingAfterTheDeadlineChargesOneNightAndLeavesTheInvoiceOpen() {
        Booking booking = bookings.create(command(roomId, OCT_3, OCT_6));
        clock.set(CHECK_IN_DAY_NOON);

        Booking cancelled = bookings.cancel(booking.getConfirmationCode(), "guest");

        assertThat(cancelled.getCancellationFee()).isEqualByComparingTo("89.00");
        assertThat(nightsHeld(roomId)).isZero();

        BookingView view = bookings.view(cancelled);
        assertThat(view.invoice().roomSubtotal()).isEqualByComparingTo("0.00");
        assertThat(view.invoice().tax()).isEqualByComparingTo("0.00");
        assertThat(view.invoice().cancellationFee()).isEqualByComparingTo("89.00");
        assertThat(view.invoice().total()).isEqualByComparingTo("89.00");
        assertThat(view.invoice().status()).isEqualTo(InvoiceStatus.OPEN);
        assertThat(view.lines()).extracting(LineView::kind)
            .containsExactly(InvoiceLineKind.CANCELLATION_FEE);
    }

    @Test
    void aCheckedInBookingCannotBeCancelled() {
        Booking booking = bookings.create(command(roomId, OCT_1, OCT_4));
        jdbc.update("update booking set status = 'CHECKED_IN' where id = ?", booking.getId());

        assertThatThrownBy(() -> bookings.cancel(booking.getConfirmationCode(), "staff"))
            .isInstanceOf(IllegalBookingStateException.class);

        assertThat(nightsHeld(roomId)).isEqualTo(3);
    }

    @Test
    void cancellingAnUnknownCodeSaysSo() {
        assertThatThrownBy(() -> bookings.cancel("INN-ZZZZZZ", "guest"))
            .isInstanceOf(NotFoundException.class);
    }

    private CreateBookingCommand command(Long room, LocalDate checkIn, LocalDate checkOut) {
        return new CreateBookingCommand(room, checkIn, checkOut, 2, 0, "Ada", "Lovelace",
            "ADA@Example.com", "940-555-0101", "High floor, please", "guest");
    }
}
