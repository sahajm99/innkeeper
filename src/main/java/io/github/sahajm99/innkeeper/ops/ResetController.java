package io.github.sahajm99.innkeeper.ops;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;

import io.github.sahajm99.innkeeper.config.InnkeeperProperties;
import io.github.sahajm99.innkeeper.service.ResetService;
import io.github.sahajm99.innkeeper.service.ResetService.Outcome;
import io.swagger.v3.oas.annotations.Hidden;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The door the nightly workflow knocks on, hidden from the documentation and from the site.
 *
 * <p>It is CSRF-exempt because the caller is curl, not a browser, and the only thing standing in
 * front of it is a shared secret compared with {@link MessageDigest#isEqual} so the comparison does
 * not leak the token one byte at a time. A deployment with no token configured - anybody who forks
 * this and runs it - answers 404, so the endpoint does not exist rather than existing unguarded.
 * </p>
 *
 * <p>The call is also what wakes a sleeping free-tier instance, so it answers quickly when the data
 * is already fresh: without {@code force} it skips a reset that happened less than half an hour
 * ago and says so.</p>
 */
@RestController
@Hidden
public class ResetController {

    /** How fresh counts as fresh. The demo resets nightly; twice in an hour helps nobody. */
    private static final Duration STALE_AFTER = Duration.ofMinutes(30);

    private static final String ACTOR = "internal";

    /** What the caller gets: whether anything happened, when the data dates from, and why. */
    public record ResetResponse(boolean performed, Instant lastResetAt, String reason) {
    }

    private final ResetService resetService;
    private final InnkeeperProperties properties;

    public ResetController(ResetService resetService, InnkeeperProperties properties) {
        this.resetService = resetService;
        this.properties = properties;
    }

    @PostMapping("/internal/reset")
    public ResponseEntity<ResetResponse> reset(
            @RequestHeader(name = "X-Reset-Token", required = false) String token,
            @RequestParam(name = "force", defaultValue = "false") boolean force) {
        String expected = properties.resetToken();
        if (expected == null || expected.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        if (!matches(token, expected)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        Outcome outcome = force
            ? resetService.reset(ACTOR)
            : resetService.resetIfStale(STALE_AFTER, ACTOR);
        ResetResponse body =
            new ResetResponse(outcome.performed(), outcome.lastResetAt(), outcome.reason());
        if (ResetService.BUSY.equals(outcome.reason())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }
        return ResponseEntity.ok(body);
    }

    private boolean matches(String token, String expected) {
        return token != null && MessageDigest.isEqual(
            token.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }
}
