package io.github.sahajm99.innkeeper.ops;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sahajm99.innkeeper.config.InnkeeperProperties;
import io.github.sahajm99.innkeeper.web.ErrorPageRenderer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The rate limiter in front of everything that writes.
 *
 * <p>It sits ahead of Spring Security rather than inside it, so a flood costs a map lookup instead
 * of a session lookup and a database round trip. Only the POSTs that create rows are limited;
 * reading is free, because a demo nobody can browse is not a demo.</p>
 *
 * <p>The refusal comes back in the shape the caller asked in: a designed page for a form post, a
 * {@code ProblemDetail} for the API.</p>
 */
public class RateLimitFilter extends OncePerRequestFilter {

    /** The write paths. Reading, signing in and the reset endpoint are not limited. */
    private static final List<String> LIMITED = List.of(
        "/book", "/complaints", "/my-bookings",
        "/api/bookings", "/api/bookings/lookup", "/api/bookings/*/cancel", "/api/demo/race");

    private static final String TITLE = "Too many requests";
    private static final String DETAIL =
        "This demo accepts twenty bookings or complaints an hour from one address. Try again later.";
    private static final String RETRY_AFTER = "3600";

    private final RateLimiter limiter;
    private final boolean enabled;
    private final ErrorPageRenderer errorPages;
    private final ObjectMapper json;
    private final AntPathMatcher paths = new AntPathMatcher();

    public RateLimitFilter(RateLimiter limiter, InnkeeperProperties properties,
            ErrorPageRenderer errorPages, ObjectMapper json) {
        this.limiter = limiter;
        this.enabled = properties.rateLimit() != null && properties.rateLimit().enabled();
        this.errorPages = errorPages;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled
            || !HttpMethod.POST.matches(request.getMethod())
            || LIMITED.stream().noneMatch(pattern -> paths.match(pattern, request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (limiter.tryAcquire(RequestLoggingFilter.clientIp(request))) {
            chain.doFilter(request, response);
            return;
        }
        refuse(request, response);
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER);
        if (request.getRequestURI().startsWith("/api/")) {
            ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, DETAIL);
            problem.setTitle(TITLE);
            problem.setInstance(URI.create(request.getRequestURI()));
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            json.writeValue(response.getWriter(), problem);
            response.flushBuffer();
            return;
        }
        errorPages.render(request, response, HttpStatus.TOO_MANY_REQUESTS.value(), DETAIL);
    }
}
