package io.github.sahajm99.innkeeper.web.form;

import java.time.LocalDate;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;

/**
 * The booking form. Only what the guest types plus the room and dates carried in from the room
 * page; the price is never a field, because the server works it out from the room.
 *
 * <p>The sizes match the columns rather than being round numbers, so a refusal comes from
 * validation with a sentence rather than from the database with a stack trace. {@code website} is
 * the honeypot: it is hidden from people and irresistible to form-filling robots, so anything in it
 * means the submission was not typed by a visitor.</p>
 */
public class BookingForm {

    @NotNull(message = "Choose a room first")
    private Long roomId;

    @NotNull(message = "Enter a check-in date")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate checkIn;

    @NotNull(message = "Enter a check-out date")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate checkOut;

    @Min(value = 1, message = "At least one adult is required")
    @Max(value = 4, message = "The largest room here sleeps 4 guests")
    private int adults = 2;

    @Min(value = 0, message = "Children cannot be a negative number")
    @Max(value = 3, message = "The largest room here sleeps 4 guests")
    private int children;

    @NotBlank(message = "Enter a first name")
    @Size(max = 80, message = "A first name can be at most 80 characters")
    private String firstName;

    @NotBlank(message = "Enter a last name")
    @Size(max = 80, message = "A last name can be at most 80 characters")
    private String lastName;

    @NotBlank(message = "Enter an email address")
    @Email(message = "Enter an email address like ada@example.com")
    @Size(max = 160, message = "An email address can be at most 160 characters")
    private String email;

    @Size(max = 32, message = "A phone number can be at most 32 characters")
    private String phone;

    @Size(max = 500, message = "Keep requests to 500 characters")
    private String specialRequests;

    /** The honeypot. Never shown, never filled in by a person. */
    private String website;

    public boolean looksAutomated() {
        return website != null && !website.isBlank();
    }

    public int getTotalGuests() {
        return adults + children;
    }

    public Long getRoomId() {
        return roomId;
    }

    public void setRoomId(Long roomId) {
        this.roomId = roomId;
    }

    public LocalDate getCheckIn() {
        return checkIn;
    }

    public void setCheckIn(LocalDate checkIn) {
        this.checkIn = checkIn;
    }

    public LocalDate getCheckOut() {
        return checkOut;
    }

    public void setCheckOut(LocalDate checkOut) {
        this.checkOut = checkOut;
    }

    public int getAdults() {
        return adults;
    }

    public void setAdults(int adults) {
        this.adults = adults;
    }

    public int getChildren() {
        return children;
    }

    public void setChildren(int children) {
        this.children = children;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getSpecialRequests() {
        return specialRequests;
    }

    public void setSpecialRequests(String specialRequests) {
        this.specialRequests = specialRequests;
    }

    public String getWebsite() {
        return website;
    }

    public void setWebsite(String website) {
        this.website = website;
    }
}
