package io.github.sahajm99.innkeeper.domain;

/** A transition the booking's current status does not allow. */
public class IllegalBookingStateException extends RuntimeException {

    public IllegalBookingStateException(String message) {
        super(message);
    }
}
