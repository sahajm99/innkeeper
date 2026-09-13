package io.github.sahajm99.innkeeper.web.form;

import io.github.sahajm99.innkeeper.model.ComplaintCategory;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A complaint, with or without a stay attached to it.
 *
 * <p>The booking code and its email are optional together: giving neither files a loose complaint
 * against the branch, and giving both attaches it to the stay - but only when the pair actually
 * matches, which {@code ComplaintService} checks, so the form cannot be used to confirm that a code
 * exists.</p>
 */
public class ComplaintForm {

    @NotNull(message = "Choose the hotel this is about")
    private Long branchId;

    @Pattern(regexp = "(?i)(|" + LookupForm.CODE_PATTERN + ")",
        message = "A confirmation code looks like INN-4KQ7ZD")
    private String bookingCode;

    @Email(message = "Enter an email address like ada@example.com")
    @Size(max = 160, message = "An email address can be at most 160 characters")
    private String bookingEmail;

    @NotBlank(message = "Enter your name")
    @Size(max = 160, message = "A name can be at most 160 characters")
    private String guestName;

    @NotBlank(message = "Enter an email address so we can answer")
    @Email(message = "Enter an email address like ada@example.com")
    @Size(max = 160, message = "An email address can be at most 160 characters")
    private String guestEmail;

    @NotNull(message = "Choose what this is about")
    private ComplaintCategory category;

    @NotBlank(message = "Tell us what went wrong")
    @Size(max = 2000, message = "Keep it to 2000 characters")
    private String description;

    /** The honeypot. */
    private String website;

    public boolean looksAutomated() {
        return website != null && !website.isBlank();
    }

    public Long getBranchId() {
        return branchId;
    }

    public void setBranchId(Long branchId) {
        this.branchId = branchId;
    }

    public String getBookingCode() {
        return bookingCode;
    }

    public void setBookingCode(String bookingCode) {
        this.bookingCode = bookingCode == null ? null : bookingCode.trim();
    }

    public String getBookingEmail() {
        return bookingEmail;
    }

    public void setBookingEmail(String bookingEmail) {
        this.bookingEmail = bookingEmail == null ? null : bookingEmail.trim();
    }

    public String getGuestName() {
        return guestName;
    }

    public void setGuestName(String guestName) {
        this.guestName = guestName;
    }

    public String getGuestEmail() {
        return guestEmail;
    }

    public void setGuestEmail(String guestEmail) {
        this.guestEmail = guestEmail == null ? null : guestEmail.trim();
    }

    public ComplaintCategory getCategory() {
        return category;
    }

    public void setCategory(ComplaintCategory category) {
        this.category = category;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getWebsite() {
        return website;
    }

    public void setWebsite(String website) {
        this.website = website;
    }
}
