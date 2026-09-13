package io.github.sahajm99.innkeeper.web;

import io.github.sahajm99.innkeeper.domain.IllegalBookingStateException;
import io.github.sahajm99.innkeeper.domain.NotFoundException;
import io.github.sahajm99.innkeeper.domain.RoomUnavailableException;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The refusals a page can get from the domain, rendered as pages rather than as stack traces.
 *
 * <p>Scoped to the web package so the JSON API keeps its own handler and its own ProblemDetail
 * bodies. Anything not named here is left to the container, which sends it to /error and the 500
 * page - with the message dropped, because server.error.include-message is never.</p>
 */
@ControllerAdvice(basePackages = "io.github.sahajm99.innkeeper.web")
public class PageExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound(NotFoundException missing, HttpServletResponse response, Model model) {
        return page(HttpStatus.NOT_FOUND, missing.getMessage(), response, model);
    }

    /**
     * A room taken between the search and the booking, a transition the status does not allow, or
     * two staff saving the same row: all three are "somebody got there first", which is a 409 and a
     * page that says what happened rather than an error.
     */
    @ExceptionHandler({
        RoomUnavailableException.class,
        IllegalBookingStateException.class,
        ObjectOptimisticLockingFailureException.class
    })
    @ResponseStatus(HttpStatus.CONFLICT)
    public String conflict(Exception clash, HttpServletResponse response, Model model) {
        String message = clash instanceof ObjectOptimisticLockingFailureException
            ? "Somebody else changed that while you were looking at it. Try again."
            : clash.getMessage();
        return page(HttpStatus.CONFLICT, message, response, model);
    }

    private String page(HttpStatus status, String message, HttpServletResponse response,
            Model model) {
        model.addAllAttributes(
            ErrorPageRenderer.model(status.value(), ErrorPageRenderer.requestId(response), message));
        return ErrorPageRenderer.templateFor(status.value());
    }
}
