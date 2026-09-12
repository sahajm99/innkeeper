package io.github.sahajm99.innkeeper.model;

/** Booking transitions recorded in the audit trail; matches ck_booking_event_type. */
public enum BookingEventType {
    CREATED,
    CHECKED_IN,
    CHECKED_OUT,
    CANCELLED,
    FINE_ADDED,
    PAYMENT_RECORDED
}
