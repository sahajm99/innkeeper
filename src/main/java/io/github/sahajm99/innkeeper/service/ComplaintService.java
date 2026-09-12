package io.github.sahajm99.innkeeper.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import io.github.sahajm99.innkeeper.domain.BookingRuleException;
import io.github.sahajm99.innkeeper.domain.ConfirmationCodes;
import io.github.sahajm99.innkeeper.domain.NotFoundException;
import io.github.sahajm99.innkeeper.model.Booking;
import io.github.sahajm99.innkeeper.model.Branch;
import io.github.sahajm99.innkeeper.model.Complaint;
import io.github.sahajm99.innkeeper.model.ComplaintCategory;
import io.github.sahajm99.innkeeper.model.ComplaintStatus;
import io.github.sahajm99.innkeeper.repository.BookingRepository;
import io.github.sahajm99.innkeeper.repository.BranchRepository;
import io.github.sahajm99.innkeeper.repository.ComplaintRepository;

import org.hibernate.Hibernate;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Complaints, from the public form to the staff queue.
 *
 * <p>A complaint may name a stay, and that is the only place this class is strict: the
 * confirmation code alone is not evidence of anything, since codes are printed on paper and
 * forwarded in email. Both halves of what the guest was given have to match before a complaint is
 * attached to a booking, and a code that does not match its email is refused rather than quietly
 * filed unlinked - otherwise the form would tell somebody their guess was wrong, which is a way of
 * telling them when it was right.</p>
 */
@Service
public class ComplaintService {

    /** Ticket numbers share the confirmation alphabet, so neither can be misread aloud. */
    public static final String TICKET_PREFIX = "CMP-";
    public static final int TICKET_BODY_LENGTH = 6;

    /** How many ticket numbers to draw before giving up; a collision is already a long shot. */
    public static final int TICKET_ATTEMPTS = 5;

    /** What the public complaint form collects. Every booking field is optional. */
    public record FileCommand(Long branchId, String bookingCode, String bookingEmail,
        String guestName, String guestEmail, ComplaintCategory category, String description) {
    }

    /** The queue the staff page works: raised, and started but not finished. */
    private static final List<ComplaintStatus> UNRESOLVED =
        List.of(ComplaintStatus.OPEN, ComplaintStatus.IN_PROGRESS);

    private final ComplaintRepository complaints;
    private final BranchRepository branches;
    private final BookingRepository bookings;
    private final Clock clock;
    private final Random random;

    public ComplaintService(ComplaintRepository complaints, BranchRepository branches,
            BookingRepository bookings, Clock clock, ObjectProvider<Random> ticketSource) {
        this.complaints = complaints;
        this.branches = branches;
        this.bookings = bookings;
        this.clock = clock;
        this.random = ticketSource.getIfAvailable(SecureRandom::new);
    }

    /** A ticket number drawn from the confirmation alphabet: {@code CMP-} and six characters. */
    public static String ticketNumber(Random random) {
        StringBuilder ticket = new StringBuilder(TICKET_PREFIX);
        for (int character = 0; character < TICKET_BODY_LENGTH; character++) {
            ticket.append(ConfirmationCodes.ALPHABET
                .charAt(random.nextInt(ConfirmationCodes.ALPHABET.length())));
        }
        return ticket.toString();
    }

    /**
     * Files the complaint and returns it with its ticket number.
     *
     * @throws BookingRuleException a booking code was given that does not match the email with it
     * @throws NotFoundException there is no such branch
     */
    @Transactional
    public Complaint file(FileCommand cmd) {
        Branch branch = branches.findById(cmd.branchId())
            .orElseThrow(() -> new NotFoundException("No branch with id " + cmd.branchId()));

        Complaint complaint = new Complaint();
        complaint.setBranch(branch);
        complaint.setBooking(bookingFor(cmd));
        complaint.setTicketNumber(nextTicketNumber());
        complaint.setGuestName(cmd.guestName());
        complaint.setGuestEmail(normalisedEmail(cmd.guestEmail()));
        complaint.setCategory(cmd.category());
        complaint.setDescription(cmd.description());
        complaint.setStatus(ComplaintStatus.OPEN);
        complaint.setCreatedAt(clock.instant());
        return complaints.save(complaint);
    }

    /** The queue: everything raised or started, oldest first, with what a page reads loaded. */
    @Transactional(readOnly = true)
    public List<Complaint> open() {
        List<Complaint> queue = complaints.findByStatusInOrderByCreatedAtAsc(UNRESOLVED);
        queue.forEach(complaint -> {
            Hibernate.initialize(complaint.getBranch());
            Hibernate.initialize(complaint.getBooking());
        });
        return queue;
    }

    @Transactional
    public Complaint start(Long id) {
        Complaint complaint = require(id);
        complaint.setStatus(ComplaintStatus.IN_PROGRESS);
        Hibernate.initialize(complaint.getBranch());
        return complaint;
    }

    /**
     * Closes the complaint with what was done about it.
     *
     * @throws IllegalArgumentException no note was written; a resolution nobody recorded is not one
     */
    @Transactional
    public Complaint resolve(Long id, String note) {
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("A resolution needs a note");
        }
        Complaint complaint = require(id);
        complaint.setStatus(ComplaintStatus.RESOLVED);
        complaint.setResolutionNote(note.trim());
        complaint.setResolvedAt(clock.instant());
        Hibernate.initialize(complaint.getBranch());
        return complaint;
    }

    // --- plumbing ---------------------------------------------------------------------------

    /** The stay behind the code and email pair, or nothing when no code was offered at all. */
    private Booking bookingFor(FileCommand cmd) {
        if (cmd.bookingCode() == null || cmd.bookingCode().isBlank()) {
            return null;
        }
        String code = cmd.bookingCode().trim().toUpperCase(Locale.ROOT);
        String email = normalisedEmail(cmd.bookingEmail());
        return bookings.findByConfirmationCode(code)
            .filter(booking -> !email.isEmpty()
                && email.equals(normalisedEmail(booking.getGuest().getEmail())))
            .orElseThrow(() -> new BookingRuleException("bookingCode",
                "That code and email do not match a booking"));
    }

    /** A ticket number nothing else is using; the unique constraint is the real guard. */
    private String nextTicketNumber() {
        for (int attempt = 1; attempt <= TICKET_ATTEMPTS; attempt++) {
            String ticket = ticketNumber(random);
            if (complaints.findByTicketNumber(ticket).isEmpty()) {
                return ticket;
            }
        }
        throw new IllegalStateException("Could not draw a free ticket number");
    }

    private Complaint require(Long id) {
        return complaints.findById(id)
            .orElseThrow(() -> new NotFoundException("No complaint with id " + id));
    }

    private String normalisedEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
