package io.github.sahajm99.innkeeper.api;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.sahajm99.innkeeper.domain.BookingRuleException;
import io.github.sahajm99.innkeeper.domain.IllegalBookingStateException;
import io.github.sahajm99.innkeeper.domain.NotFoundException;
import io.github.sahajm99.innkeeper.domain.RoomUnavailableException;
import io.github.sahajm99.innkeeper.service.ParkingUnavailableException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Every refusal the API can give, as a {@code ProblemDetail}.
 *
 * <p>Three kinds, and the status is the whole message: 400 when the request is wrong and the client
 * can fix it, with an {@code errors} map naming the field; 404 when nothing matches - including a
 * code with the wrong email, so the endpoint is not an oracle for confirmation codes; 409 when the
 * request was fine but the world moved, which is the race this application is about. 429 never
 * reaches here, because the rate limiter answers in front of Spring Security.</p>
 *
 * <p>It is scoped to the API package, so the pages keep their own handler and their own designed
 * error pages, and ordered first so it wins over any global advice Spring Boot contributes.</p>
 */
@RestControllerAdvice(basePackages = "io.github.sahajm99.innkeeper.api")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler {

    private static final String ERRORS = "errors";

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    // --- 400: the request is wrong ----------------------------------------------------------------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail invalidBody(MethodArgumentNotValidException invalid) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError field : invalid.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(field.getField(), field.getDefaultMessage());
        }
        invalid.getBindingResult().getGlobalErrors()
            .forEach(error -> errors.putIfAbsent(error.getObjectName(), error.getDefaultMessage()));
        return badRequest("Check the highlighted fields.", errors);
    }

    @ExceptionHandler(BookingRuleException.class)
    public ProblemDetail brokenRule(BookingRuleException broken) {
        return badRequest(broken.getMessage(), Map.of(broken.field(), broken.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail unusable(IllegalArgumentException unusable) {
        return badRequest(unusable.getMessage(), Map.of());
    }

    /** A date, a number or an enum in the query string that is not one. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail unparseableParameter(MethodArgumentTypeMismatchException mismatch) {
        String message = "Not a valid " + expectedType(mismatch) + ".";
        return badRequest(message, Map.of(mismatch.getName(), message));
    }

    /** A body that is not JSON, or a date inside it that is not a date. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail unreadableBody(HttpMessageNotReadableException unreadable) {
        return badRequest("The request body could not be read as JSON.", Map.of());
    }

    // --- 404: nothing matches -----------------------------------------------------------------------

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail notFound(NotFoundException missing) {
        ProblemDetail problem =
            ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, missing.getMessage());
        problem.setTitle("Not found");
        return problem;
    }

    // --- 409: somebody got there first ---------------------------------------------------------------

    @ExceptionHandler(RoomUnavailableException.class)
    public ProblemDetail roomTaken(RoomUnavailableException taken) {
        return conflict("Room unavailable", taken.getMessage());
    }

    @ExceptionHandler(ParkingUnavailableException.class)
    public ProblemDetail noParking(ParkingUnavailableException taken) {
        return conflict("Parking unavailable", taken.getMessage());
    }

    @ExceptionHandler(IllegalBookingStateException.class)
    public ProblemDetail wrongState(IllegalBookingStateException refused) {
        return conflict("Not possible now", refused.getMessage());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail staleWrite(ObjectOptimisticLockingFailureException stale) {
        return conflict("Changed by somebody else",
            "That booking was changed while this request was in flight. Read it again.");
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail refused(IllegalStateException refused) {
        return conflict("Not possible now", refused.getMessage());
    }

    // --- anything else ------------------------------------------------------------------------------

    /**
     * A bug, not a refusal. The client gets a problem document with no internals in it and the log
     * gets the stack trace, which is the only place it belongs.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception failure) {
        log.error("Unhandled failure in the API", failure);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong on our side.");
        problem.setTitle("Server error");
        return problem;
    }

    private ProblemDetail badRequest(String detail, Map<String, String> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Invalid request");
        problem.setProperty(ERRORS, errors);
        return problem;
    }

    private ProblemDetail conflict(String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail);
        problem.setTitle(title);
        return problem;
    }

    private String expectedType(MethodArgumentTypeMismatchException mismatch) {
        Class<?> required = mismatch.getRequiredType();
        return required == null ? "value" : required.getSimpleName();
    }
}
