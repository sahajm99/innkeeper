package io.github.sahajm99.innkeeper.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Everything the container sends to /error, and the designed pages by name.
 *
 * <p>Implementing {@link ErrorController} takes the whole /error mapping from Spring Boot, which is
 * the point: the white-label page prints the exception and this one prints the request id instead,
 * which is the only thing a visitor can usefully quote back. A status with no page of its own is
 * rendered by the server-error page but keeps its own status.</p>
 */
@Controller
public class ErrorPagesController implements ErrorController {

    @RequestMapping("/error")
    public String dispatched(HttpServletRequest request, HttpServletResponse response, Model model) {
        return render(statusOf(request), response, model);
    }

    /**
     * The pages by name, so a link in a report or a rate-limited request can reach one directly.
     * Anything outside the designed set is a 404 from the router rather than an invented page.
     */
    @RequestMapping("/error/{status:403|404|409|429|500}")
    public String designed(@PathVariable("status") int status, HttpServletResponse response,
            Model model) {
        return render(status, response, model);
    }

    private String render(int status, HttpServletResponse response, Model model) {
        response.setStatus(status);
        model.addAllAttributes(
            ErrorPageRenderer.model(status, ErrorPageRenderer.requestId(response), null));
        return ErrorPageRenderer.templateFor(status);
    }

    /** The status the container recorded, or 500 when a request reached /error without one. */
    private int statusOf(HttpServletRequest request) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (status instanceof Integer code && code >= 100 && code < 600) {
            return code;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR.value();
    }
}
