package io.github.sahajm99.innkeeper.web;

import java.util.Arrays;

import io.github.sahajm99.innkeeper.domain.BookingRuleException;
import io.github.sahajm99.innkeeper.domain.NotFoundException;
import io.github.sahajm99.innkeeper.model.Complaint;
import io.github.sahajm99.innkeeper.model.ComplaintCategory;
import io.github.sahajm99.innkeeper.repository.BranchRepository;
import io.github.sahajm99.innkeeper.repository.ComplaintRepository;
import io.github.sahajm99.innkeeper.service.ComplaintService;
import io.github.sahajm99.innkeeper.web.form.ComplaintForm;
import jakarta.validation.Valid;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * The complaint form and the ticket it produces.
 *
 * <p>A booking can be attached to the complaint, but only by giving the confirmation code and the
 * email together: a code on its own would let anybody hang a complaint on somebody else's stay, and
 * would tell them whether the code exists. The thanks page shows the ticket number and nothing the
 * complainer wrote, because it has no more proof of who is reading it than the ticket number.</p>
 */
@Controller
public class ComplaintController {

    private final ComplaintService complaints;
    private final ComplaintRepository complaintRepository;
    private final BranchRepository branches;

    public ComplaintController(ComplaintService complaints, ComplaintRepository complaintRepository,
            BranchRepository branches) {
        this.complaints = complaints;
        this.complaintRepository = complaintRepository;
        this.branches = branches;
    }

    @GetMapping("/complaints/new")
    public String form(@ModelAttribute("complaint") ComplaintForm form, Model model) {
        addChoices(model);
        return "complaint";
    }

    @PostMapping("/complaints/new")
    public String file(@Valid @ModelAttribute("complaint") ComplaintForm form,
            BindingResult binding, Model model) {
        if (form.looksAutomated()) {
            return "redirect:/";
        }
        addChoices(model);
        if (binding.hasErrors()) {
            return "complaint";
        }
        try {
            Complaint filed = complaints.file(new ComplaintService.FileCommand(form.getBranchId(),
                form.getBookingCode(), form.getBookingEmail(), form.getGuestName().trim(),
                form.getGuestEmail(), form.getCategory(), form.getDescription().trim()));
            return "redirect:/complaints/" + filed.getTicketNumber();
        } catch (BookingRuleException mismatch) {
            binding.rejectValue(mismatch.field(), "complaint.booking", mismatch.getMessage());
            return "complaint";
        }
    }

    @GetMapping("/complaints/{ticket}")
    public String filed(@PathVariable("ticket") String ticket, Model model) {
        Complaint complaint = complaintRepository.findDetailedByTicketNumber(ticket)
            .orElseThrow(() -> new NotFoundException("No complaint with ticket " + ticket));
        model.addAttribute("ticket", complaint.getTicketNumber());
        model.addAttribute("branchName", complaint.getBranch().getName());
        model.addAttribute("category", label(complaint.getCategory()));
        return "complaint-filed";
    }

    private void addChoices(Model model) {
        model.addAttribute("branchList", branches.findAllByOrderByName());
        model.addAttribute("categories", Arrays.stream(ComplaintCategory.values())
            .map(category -> new CategoryChoice(category, label(category)))
            .toList());
    }

    /** One option of the category select: the stored name and the words a guest would use. */
    public record CategoryChoice(ComplaintCategory value, String label) {
    }

    /** The categories written the way a guest would say them rather than the way they are stored. */
    static String label(ComplaintCategory category) {
        return switch (category) {
            case ROOM -> "The room";
            case SERVICE -> "Service";
            case NOISE -> "Noise";
            case BILLING -> "The bill";
            case OTHER -> "Something else";
        };
    }
}
