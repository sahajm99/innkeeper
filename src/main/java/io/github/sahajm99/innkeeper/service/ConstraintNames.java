package io.github.sahajm99.innkeeper.service;

import java.util.Locale;

import org.hibernate.exception.ConstraintViolationException;

import org.springframework.dao.DataAccessException;

/**
 * Names the database constraint a failure came from, so a service can tell one unique violation
 * apart from another and answer differently: a clash on the room nights means the room is gone, a
 * clash on the confirmation code just means the next code should be tried.
 *
 * <p>Spring reports every one of them as the same {@code DataIntegrityViolationException}, and the
 * name is buried in the cause chain, so this walks it. Databases spell the name differently -
 * PostgreSQL reports {@code uq_room_night}, H2 reports {@code public.uq_room_night_index_4} - so
 * callers should match with {@code contains} rather than equality.</p>
 */
public final class ConstraintNames {

    private ConstraintNames() {
    }

    /**
     * The lower-cased constraint name from the first {@link ConstraintViolationException} in the
     * cause chain that carries one, or the lower-cased message of the deepest cause when no driver
     * named it. Never null, so a caller can match on it without a guard.
     */
    public static String of(DataAccessException failure) {
        Throwable deepest = failure;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                && violation.getConstraintName() != null) {
                return violation.getConstraintName().toLowerCase(Locale.ROOT);
            }
            deepest = cause;
            if (cause.getCause() == cause) {
                break;
            }
        }
        String message = deepest.getMessage();
        return message == null ? "" : message.toLowerCase(Locale.ROOT);
    }
}
