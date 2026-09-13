package io.github.sahajm99.innkeeper.ops;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * One INFO line per request, and one id that ties that line to the response, to an error page and
 * to whatever the visitor quotes back.
 *
 * <p>The fields go into the MDC rather than into the message, because the {@code prod} and
 * {@code demo} profiles set {@code logging.structured.format.console=ecs}: there the line is one
 * JSON object with these as fields, and locally it is the same sentence in plain text. The path is
 * recorded without its query string, so a confirmation code or an email in a link never reaches the
 * log.</p>
 */
public class RequestLoggingFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** MDC keys, listed so the same names can be asserted on and grepped for. */
    public static final String REQUEST_ID = "request_id";
    public static final String METHOD = "http_method";
    public static final String PATH = "http_path";
    public static final String STATUS = "http_status";
    public static final String DURATION = "duration_ms";
    public static final String PRINCIPAL = "principal";
    public static final String CLIENT_IP = "client_ip";

    private static final List<String> KEYS =
        List.of(REQUEST_ID, METHOD, PATH, STATUS, DURATION, PRINCIPAL, CLIENT_IP);

    /** Long enough to be an id, short enough to log, and nothing that could be an injection. */
    private static final Pattern ACCEPTABLE = Pattern.compile("[A-Za-z0-9-]{8,64}");

    /** Assets say nothing about what the application did, and there are a lot of them. */
    private static final List<String> SKIPPED =
        List.of("/static", "/css", "/js", "/fonts", "/favicon.svg", "/robots.txt");

    private static final String ANONYMOUS = "anonymous";
    private static final String FORWARDED_FOR = "X-Forwarded-For";

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    /**
     * The address the request came from: the first hop of {@code X-Forwarded-For} when a proxy put
     * one there, and the socket address otherwise. The first hop is the client; the rest are the
     * proxies, and the limiter and the log both want the client.
     */
    public static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader(FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            String firstHop = forwarded.split(",")[0].trim();
            if (!firstHop.isEmpty()) {
                return firstHop;
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return SKIPPED.stream()
            .anyMatch(skipped -> path.equals(skipped) || path.startsWith(skipped + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        response.setHeader(REQUEST_ID_HEADER, requestIdOf(request));
        long startedAt = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            record(request, response, (System.nanoTime() - startedAt) / 1_000_000L);
        }
    }

    private void record(HttpServletRequest request, HttpServletResponse response, long millis) {
        MDC.put(REQUEST_ID, response.getHeader(REQUEST_ID_HEADER));
        MDC.put(METHOD, request.getMethod());
        MDC.put(PATH, request.getRequestURI());
        MDC.put(STATUS, String.valueOf(response.getStatus()));
        MDC.put(DURATION, String.valueOf(millis));
        MDC.put(PRINCIPAL, principalOf(request));
        MDC.put(CLIENT_IP, clientIp(request));
        try {
            log.info("request {} {} -> {} in {} ms", request.getMethod(), request.getRequestURI(),
                response.getStatus(), millis);
        } finally {
            KEYS.forEach(MDC::remove);
        }
    }

    /** An incoming id is kept so a caller can follow one request across services; ours otherwise. */
    private String requestIdOf(HttpServletRequest request) {
        String incoming = request.getHeader(REQUEST_ID_HEADER);
        if (incoming != null && ACCEPTABLE.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }

    /**
     * Who was signed in, as far as this filter can still tell.
     *
     * <p>It runs in front of Spring Security, so by the time the chain comes back the security
     * context has been cleared and the wrapped request that knew the principal is gone. The session
     * still holds it, which is enough for the page half of the site; the API is stateless and
     * anonymous, which is what it says.</p>
     */
    private String principalOf(HttpServletRequest request) {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        if (isSignedIn(current)) {
            return current.getName();
        }
        if (request.getUserPrincipal() != null) {
            return request.getUserPrincipal().getName();
        }
        return fromSession(request);
    }

    private String fromSession(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) {
            return ANONYMOUS;
        }
        Object stored = session.getAttribute("SPRING_SECURITY_CONTEXT");
        if (stored instanceof SecurityContext context && isSignedIn(context.getAuthentication())) {
            return context.getAuthentication().getName();
        }
        return ANONYMOUS;
    }

    private boolean isSignedIn(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
            && !"anonymousUser".equals(authentication.getName());
    }
}
