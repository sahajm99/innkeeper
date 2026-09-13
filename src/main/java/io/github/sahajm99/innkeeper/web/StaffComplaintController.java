package io.github.sahajm99.innkeeper.web;

import java.time.Instant;
import java.util.List;

import io.github.sahajm99.innkeeper.model.Complaint;
import io.github.sahajm99.innkeeper.model.ComplaintCategory;
import io.github.sahajm99.innkeeper.model.ComplaintStatus;
import io.github.sahajm99.innkeeper.service.ComplaintService;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The complaints queue.
 *
 * <p>Only what is still open is here: a resolved complaint has a note on it and has left the
 * queue, which is the difference between a list of work and an archive. Resolving needs that note,
 * so the button sits behind the field rather than beside it.</p>
 */
@Controller
public class StaffComplaintController {

    /** One row of the queue, with the guest's address already reduced to what a desk may see. */
    public record Row(Long id, String ticketNumber, String branchName, ComplaintCategory category,
        ComplaintStatus status, String guestName, String guestEmail, String bookingCode,
        String description, Instant createdAt) {
    }

    private final ComplaintService complaints;

    public StaffComplaintController(ComplaintService complaints) {
        this.complaints = complaints;
    }

    @GetMapping("/staff/complaints")
    public String queue(Model model) {
        List<Row> queue = complaints.open().stream().map(this::row).toList();
        model.addAttribute("complaints", queue);
        return "staff/complaints";
    }

    @PostMapping("/staff/complaints/{id}/start")
    public String start(@PathVariable("id") Long id, RedirectAttributes attributes) {
        Complaint complaint = complaints.start(id);
        Flash.message(attributes, "Complaint " + complaint.getTicketNumber() + " started.");
        return "redirect:/staff/complaints";
    }

    @PostMapping("/staff/complaints/{id}/resolve")
    public String resolve(@PathVariable("id") Long id,
            @RequestParam(name = "note", required = false) String note,
            RedirectAttributes attributes) {
        try {
            Complaint complaint = complaints.resolve(id, note);
            Flash.message(attributes, "Complaint " + complaint.getTicketNumber() + " resolved.");
        } catch (IllegalArgumentException refused) {
            Flash.error(attributes, refused.getMessage());
        }
        return "redirect:/staff/complaints";
    }

    private Row row(Complaint complaint) {
        return new Row(
            complaint.getId(),
            complaint.getTicketNumber(),
            complaint.getBranch().getName(),
            complaint.getCategory(),
            complaint.getStatus(),
            complaint.getGuestName(),
            complaint.getGuestEmail(),
            complaint.getBooking() == null ? null : complaint.getBooking().getConfirmationCode(),
            complaint.getDescription(),
            complaint.getCreatedAt());
    }
}
