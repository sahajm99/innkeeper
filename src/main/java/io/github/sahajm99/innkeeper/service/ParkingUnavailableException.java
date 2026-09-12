package io.github.sahajm99.innkeeper.service;

/**
 * The parking space was taken between the moment the desk listed it and the moment it was
 * assigned. Assignment is a conditional update, so the loser of that race finds out here.
 */
public class ParkingUnavailableException extends RuntimeException {

    public ParkingUnavailableException(String message) {
        super(message);
    }
}
