package io.github.sahajm99.innkeeper.domain;

/** A booking request that breaks a domain rule; {@link #field()} names the form field at fault. */
public class BookingRuleException extends RuntimeException {

    private final String field;

    public BookingRuleException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
