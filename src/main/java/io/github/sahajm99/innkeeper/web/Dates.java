package io.github.sahajm99.innkeeper.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * How a date and an amount of money look on every page.
 *
 * <p>One object rather than a formatter per template, because a date written two ways on two pages
 * is a bug a reader notices before any test does. Every page gets it as the model attribute
 * {@code dates}, so a template writes {@code ${dates.format(booking.checkIn)}} and never builds a
 * pattern of its own.</p>
 *
 * <p>The formats are fixed, not localised: "Fri 3 Oct 2026" is unambiguous to a reader on either
 * side of the Atlantic, which the numeric orderings are not, and the money is US dollars because
 * the hotels are in Texas.</p>
 */
public class Dates {

    /** "Fri 3 Oct 2026". */
    private static final DateTimeFormatter FULL =
        DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);

    /** "3 Oct", for the two ends of a range written in one sentence. */
    private static final DateTimeFormatter SHORT =
        DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    /** "Oct", the month marker on the availability strip. */
    private static final DateTimeFormatter MONTH =
        DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH);

    /** "3:00 PM, Fri 3 Oct 2026": an instant written out in full. */
    private static final DateTimeFormatter MOMENT =
        DateTimeFormatter.ofPattern("h:mm a, EEE d MMM yyyy", Locale.ENGLISH);

    /** "3 PM, Wed 1 Oct", lower-cased before it is printed. */
    private static final DateTimeFormatter DEADLINE =
        DateTimeFormatter.ofPattern("h a, EEE d MMM", Locale.ENGLISH);

    private final ZoneId zone;

    public Dates(ZoneId zone) {
        this.zone = zone;
    }

    /** The timezone the group keeps its books in; instants are printed in it. */
    public ZoneId zone() {
        return zone;
    }

    /** "Fri 3 Oct 2026", or an empty string for a missing date. */
    public String format(LocalDate date) {
        return date == null ? "" : FULL.format(date);
    }

    /** "3 Oct", for "Not available 3 to 6 Oct". */
    public String dayMonth(LocalDate date) {
        return date == null ? "" : SHORT.format(date);
    }

    /** "Oct", printed in the strip cell that starts a month. */
    public String month(LocalDate date) {
        return date == null ? "" : MONTH.format(date);
    }

    /** The same instant as a day in the group timezone. */
    public String format(Instant instant) {
        return instant == null ? "" : FULL.format(instant.atZone(zone));
    }

    /** "3:00 PM, Fri 3 Oct 2026" in the zone given, for a deadline that belongs to a branch. */
    public String moment(Instant instant, ZoneId at) {
        return instant == null ? "" : MOMENT.format(instant.atZone(at == null ? zone : at));
    }

    public String moment(Instant instant) {
        return moment(instant, zone);
    }

    /**
     * "3 pm, Wed 1 Oct": the cancellation deadline, which a guest reads as a time of day rather
     * than as a timestamp. The minutes are dropped because the deadline is always on the hour, and
     * the meridiem is lower case because that is how the sentence around it is written.
     */
    public String deadline(Instant instant, ZoneId at) {
        return instant == null
            ? ""
            : DEADLINE.format(instant.atZone(at == null ? zone : at))
                .replace("AM", "am").replace("PM", "pm");
    }

    /** "$1,234.50". Null is an empty string rather than "$0.00", which would be a claim. */
    public String money(BigDecimal amount) {
        return amount == null ? "" : String.format(Locale.US, "$%,.2f", amount);
    }

    /** "1 night" or "3 nights", so no template has to remember the plural. */
    public String nights(long nights) {
        return nights == 1 ? "1 night" : nights + " nights";
    }

    /** "1 guest" or "2 guests". */
    public String guests(long guests) {
        return guests == 1 ? "1 guest" : guests + " guests";
    }
}
