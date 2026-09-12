package io.github.sahajm99.innkeeper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.LocalDate;
import java.util.Random;

import io.github.sahajm99.innkeeper.domain.BookingRuleException;
import io.github.sahajm99.innkeeper.domain.BookingStatus;
import io.github.sahajm99.innkeeper.domain.NotFoundException;
import io.github.sahajm99.innkeeper.model.Booking;
import io.github.sahajm99.innkeeper.model.Branch;
import io.github.sahajm99.innkeeper.model.Complaint;
import io.github.sahajm99.innkeeper.model.ComplaintCategory;
import io.github.sahajm99.innkeeper.model.ComplaintStatus;
import io.github.sahajm99.innkeeper.model.Room;
import io.github.sahajm99.innkeeper.service.ComplaintService.FileCommand;
import io.github.sahajm99.innkeeper.support.AbstractServiceTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Complaints from the public form. A complaint may name a stay, but only the guest who holds both
 * halves of it can attach one: the confirmation code alone is not evidence, so a code without its
 * email is refused rather than quietly filed unlinked.
 */
class ComplaintServiceTest extends AbstractServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 12);
    private static final String STORY = "The heater rattles all night and nobody sleeps.";

    @Autowired ComplaintService complaints;
    @Autowired Random confirmationCodeRandom;

    private Long branchId;
    private Booking booking;

    @BeforeEach
    void createTheBranchAndTheStay() {
        booking = inTransaction(data -> {
            Branch denton = data.branch("DEN");
            Room room = data.room(denton, data.roomType("STANDARD", 2), "101", "89.00");
            branchId = denton.getId();
            return data.booking(room, data.guest("Grace", "Example", "grace.example@example.com"),
                TODAY.minusDays(2), TODAY.plusDays(1), BookingStatus.CHECKED_IN);
        });
    }

    @Test
    void aMatchingCodeAndEmailAttachTheStayToTheComplaint() {
        Complaint filed = complaints.file(about(booking.getConfirmationCode(),
            "GRACE.Example@example.com"));

        assertThat(filed.getBooking()).isNotNull();
        assertThat(filed.getBooking().getId()).isEqualTo(booking.getId());
        assertThat(filed.getStatus()).isEqualTo(ComplaintStatus.OPEN);
        assertThat(filed.getCreatedAt()).isEqualTo(clock.instant());
        assertThat(filed.getTicketNumber()).matches("CMP-[A-Z2-9]{6}");
        assertThat(filed.getBranch().getCode()).isEqualTo("DEN");
    }

    @Test
    void aCodeWithTheWrongEmailIsRefusedRatherThanFiledUnlinked() {
        String code = booking.getConfirmationCode();
        BookingRuleException refusal = catchThrowableOfType(BookingRuleException.class,
            () -> complaints.file(about(code, "someone.else@example.com")));

        assertThat(refusal).hasMessage("That code and email do not match a booking");
        assertThat(refusal.field()).isEqualTo("bookingCode");
        assertThat(count("complaint")).isZero();
    }

    @Test
    void anUnknownCodeIsRefusedTheSameWay() {
        assertThatThrownBy(() -> complaints.file(about("INN-ZZZZZZ", "grace.example@example.com")))
            .isInstanceOf(BookingRuleException.class);

        assertThat(count("complaint")).isZero();
    }

    @Test
    void aComplaintWithNoCodeAtAllIsFiledWithoutAStay() {
        Complaint filed = complaints.file(about(null, null));

        assertThat(filed.getBooking()).isNull();
        assertThat(filed.getTicketNumber()).matches("CMP-[A-Z2-9]{6}");
        assertThat(filed.getGuestEmail()).isEqualTo("grace.example@example.com");
    }

    @Test
    void anUnknownBranchIsNotFound() {
        assertThatThrownBy(() -> complaints.file(new FileCommand(-1L, null, null, "Grace Example",
            "grace.example@example.com", ComplaintCategory.ROOM, STORY)))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void aTicketNumberCollisionIsRetriedUntilAFreeOneComesUp() {
        long seed = 20260912L;
        Random probe = new Random(seed);
        String taken = ComplaintService.ticketNumber(probe);
        String next = ComplaintService.ticketNumber(probe);

        confirmationCodeRandom.setSeed(seed);
        Complaint first = complaints.file(about(null, null));
        confirmationCodeRandom.setSeed(seed);
        Complaint second = complaints.file(about(null, null));

        assertThat(first.getTicketNumber()).isEqualTo(taken);
        assertThat(second.getTicketNumber()).isEqualTo(next).isNotEqualTo(taken);
    }

    @Test
    void workingAComplaintTakesItFromOpenThroughInProgressToResolved() {
        Complaint filed = complaints.file(about(null, null));

        Complaint started = complaints.start(filed.getId());
        assertThat(started.getStatus()).isEqualTo(ComplaintStatus.IN_PROGRESS);
        assertThat(complaints.open()).extracting(Complaint::getId).contains(filed.getId());

        Complaint resolved = complaints.resolve(filed.getId(), "Moved the guest to a quiet room.");

        assertThat(resolved.getStatus()).isEqualTo(ComplaintStatus.RESOLVED);
        assertThat(resolved.getResolutionNote()).isEqualTo("Moved the guest to a quiet room.");
        assertThat(resolved.getResolvedAt()).isEqualTo(clock.instant());
        assertThat(complaints.open()).isEmpty();
    }

    @Test
    void resolvingWithoutSayingWhatWasDoneIsRefused() {
        Complaint filed = complaints.file(about(null, null));

        assertThatThrownBy(() -> complaints.resolve(filed.getId(), "   "))
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(complaints.open()).hasSize(1);
    }

    @Test
    void openIsEverythingNobodyHasFinishedWith() {
        Complaint first = complaints.file(about(null, null));
        Complaint second = complaints.file(about(null, null));
        complaints.resolve(first.getId(), "Apologised and refunded a night.");

        assertThat(complaints.open()).extracting(Complaint::getId)
            .containsExactly(second.getId());
        assertThat(complaints.open().get(0).getBranch().getCode())
            .as("the branch comes back with the complaint").isEqualTo("DEN");
    }

    private FileCommand about(String bookingCode, String bookingEmail) {
        return new FileCommand(branchId, bookingCode, bookingEmail, "Grace Example",
            "grace.example@example.com", ComplaintCategory.ROOM, STORY);
    }
}
