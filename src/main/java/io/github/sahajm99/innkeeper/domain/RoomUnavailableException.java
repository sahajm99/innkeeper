package io.github.sahajm99.innkeeper.domain;

/** The room is already held for one of the requested nights. */
public class RoomUnavailableException extends RuntimeException {

    public RoomUnavailableException(String message) {
        super(message);
    }
}
