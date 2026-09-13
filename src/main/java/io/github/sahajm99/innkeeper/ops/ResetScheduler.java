package io.github.sahajm99.innkeeper.ops;

import java.time.Duration;

import io.github.sahajm99.innkeeper.service.ResetService;
import io.github.sahajm99.innkeeper.service.ResetService.Outcome;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Three in the morning, Central time: the demo forgets whatever visitors did to it.
 *
 * <p>There is a scheduled workflow that calls the endpoint as well, because a free-tier instance
 * asleep at three has no scheduler running. Whichever arrives first does the work and the other is
 * told the data is already fresh, which is why this asks for a reset only if one is due.</p>
 */
@Component
public class ResetScheduler {

    private static final Duration STALE_AFTER = Duration.ofMinutes(30);
    private static final String ACTOR = "scheduler";

    private static final Logger log = LoggerFactory.getLogger(ResetScheduler.class);

    private final ResetService resetService;

    public ResetScheduler(ResetService resetService) {
        this.resetService = resetService;
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "America/Chicago")
    public void nightly() {
        Outcome outcome = resetService.resetIfStale(STALE_AFTER, ACTOR);
        log.info("nightly reset: performed={} reason={}", outcome.performed(), outcome.reason());
    }
}
