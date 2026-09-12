package io.github.sahajm99.innkeeper.domain;

/** Nothing matches the identifier the visitor asked for. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
