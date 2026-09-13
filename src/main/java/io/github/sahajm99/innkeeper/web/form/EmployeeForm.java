package io.github.sahajm99.innkeeper.web.form;

import java.time.LocalDate;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;

/**
 * Somebody joining the group.
 *
 * <p>The email is the key an account is later linked by, so it is the one field a manager cannot
 * repeat; the refusal comes from the unique constraint rather than from a lookup here, which is
 * the only version that is still true when two managers hire at once.</p>
 */
public class EmployeeForm {

    @NotNull(message = "Choose the hotel they work at")
    private Long branchId;

    @NotBlank(message = "Enter a first name")
    @Size(max = 80, message = "Keep the first name to 80 characters")
    private String firstName;

    @NotBlank(message = "Enter a last name")
    @Size(max = 80, message = "Keep the last name to 80 characters")
    private String lastName;

    @NotBlank(message = "Enter a work email address")
    @Email(message = "Enter an email address like ben.sample@example.com")
    @Size(max = 160, message = "An email address can be at most 160 characters")
    private String email;

    @NotBlank(message = "Enter the job they do")
    @Size(max = 60, message = "Keep the position to 60 characters")
    private String position;

    @NotNull(message = "Enter the day they started")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate hiredOn;

    public Long getBranchId() {
        return branchId;
    }

    public void setBranchId(Long branchId) {
        this.branchId = branchId;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName == null ? null : firstName.trim();
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName == null ? null : lastName.trim();
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email == null ? null : email.trim();
    }

    public String getPosition() {
        return position;
    }

    public void setPosition(String position) {
        this.position = position == null ? null : position.trim();
    }

    public LocalDate getHiredOn() {
        return hiredOn;
    }

    public void setHiredOn(LocalDate hiredOn) {
        this.hiredOn = hiredOn;
    }
}
