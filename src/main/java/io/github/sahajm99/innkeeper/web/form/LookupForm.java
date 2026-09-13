package io.github.sahajm99.innkeeper.web.form;

import java.util.Locale;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The two things that open a booking: the code on the confirmation and the email it was made with.
 *
 * <p>Both are required and both are checked together, so a right code with a wrong email says
 * exactly what a wrong code with a wrong email says. Answering differently would make the form a
 * way of finding out which codes exist.</p>
 */
public class LookupForm {

    /** The shape {@code ConfirmationCodes} draws: INN- and six characters, no I, O, 0 or 1. */
    public static final String CODE_PATTERN = "INN-[A-Za-z2-9]{6}";

    @NotBlank(message = "Enter your confirmation code")
    @Pattern(regexp = "(?i)" + CODE_PATTERN, message = "A confirmation code looks like INN-4KQ7ZD")
    private String code;

    @NotBlank(message = "Enter the email used for the booking")
    @Email(message = "Enter an email address like ada@example.com")
    @Size(max = 160, message = "An email address can be at most 160 characters")
    private String email;

    /** The code as the database holds it: trimmed and upper case. */
    public String normalisedCode() {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code == null ? null : code.trim();
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email == null ? null : email.trim();
    }
}
