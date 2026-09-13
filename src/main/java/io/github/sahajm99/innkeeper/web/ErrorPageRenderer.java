package io.github.sahajm99.innkeeper.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

/**
 * Renders a designed error page from somewhere that has no controller behind it: the access-denied
 * handler inside the security chain and the rate limiter in front of it.
 *
 * <p>Both could forward to {@code /error/403} and let Spring MVC do this, but a forward from a
 * filter depends on the servlet dispatcher and skips the rest of the chain, and what comes back is
 * then hard to assert on. Rendering the same Thymeleaf template directly keeps the pages where the
 * designer expects them - {@code templates/error} - and makes the answer the same everywhere.</p>
 */
@Component
public class ErrorPageRenderer {

    /** Set by the request log; error pages print it so a report can name one request. */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** What the pages print for a request that skipped the log, such as a static asset. */
    public static final String NO_REQUEST_ID = "n/a";

    /** The statuses with a page of their own. Anything else is rendered by the 500 page. */
    public static final Set<Integer> DESIGNED = Set.of(403, 404, 409, 429, 500);

    private final SpringTemplateEngine templateEngine;

    public ErrorPageRenderer(SpringTemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    /** The template name for a status, falling back to the server-error page. */
    public static String templateFor(int status) {
        return "error/" + (DESIGNED.contains(status) ? status : 500);
    }

    /** The id the request log put on the response, or {@value #NO_REQUEST_ID}. */
    public static String requestId(HttpServletResponse response) {
        String id = response.getHeader(REQUEST_ID_HEADER);
        return id == null || id.isBlank() ? NO_REQUEST_ID : id;
    }

    /** The model every error page reads: the status, the request id and an optional sentence. */
    public static Map<String, Object> model(int status, String requestId, String message) {
        Map<String, Object> model = new HashMap<>();
        model.put("status", status);
        model.put("requestId", requestId);
        model.put("message", message);
        return model;
    }

    /** Writes the page for the status straight to the response. */
    public void render(HttpServletRequest request, HttpServletResponse response, int status,
            String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.TEXT_HTML_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        WebContext context = new WebContext(
            JakartaServletWebApplication.buildApplication(request.getServletContext())
                .buildExchange(request, response),
            request.getLocale(),
            model(status, requestId(response), message));
        templateEngine.process(templateFor(status), context, response.getWriter());
        response.flushBuffer();
    }
}
