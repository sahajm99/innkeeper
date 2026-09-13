package io.github.sahajm99.innkeeper.web.form;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.format.annotation.DateTimeFormat;

/**
 * What the visitor is looking for: dates, a party size and optional filters.
 *
 * <p>A mutable bean rather than a record because Spring binds it from the query string on every
 * page that shows the search - the front page, the rooms list and the links between them - and a
 * failed bind has to leave the other fields in place so the form can be redrawn with what was
 * typed. Nothing here is required: {@code /rooms} with no query string is a legitimate page.</p>
 */
public class SearchForm {

    /** The biggest room in the group sleeps four; asking for more is a mistake worth naming. */
    public static final int MAX_GUESTS = 4;

    private Long branchId;

    private String type;

    @DecimalMin(value = "0.00", message = "A nightly rate cannot be negative")
    private BigDecimal maxRate;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate checkIn;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate checkOut;

    @Min(value = 1, message = "A stay needs at least one guest")
    @Max(value = MAX_GUESTS, message = "The largest room here sleeps 4 guests")
    private int guests = 2;

    /** True once the visitor has asked for particular nights, which is when totals can be shown. */
    public boolean hasDates() {
        return checkIn != null && checkOut != null;
    }

    public Long getBranchId() {
        return branchId;
    }

    public void setBranchId(Long branchId) {
        this.branchId = branchId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public BigDecimal getMaxRate() {
        return maxRate;
    }

    public void setMaxRate(BigDecimal maxRate) {
        this.maxRate = maxRate;
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

    public int getGuests() {
        return guests;
    }

    public void setGuests(int guests) {
        this.guests = guests;
    }
}
