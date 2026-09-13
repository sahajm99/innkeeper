package io.github.sahajm99.innkeeper.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

import io.github.sahajm99.innkeeper.model.AppMetadata;
import io.github.sahajm99.innkeeper.repository.AppMetadataRepository;
import io.github.sahajm99.innkeeper.seed.SeedData;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Puts the demo back the way it shipped: every table emptied child-first and re-seeded by the same
 * class the V2 migration runs, in one transaction.
 *
 * <p>One transaction is the whole point. A visitor mid-booking while this runs either sees the old
 * data or the new, never a half-deleted database - and if the seed fails, the delete goes with it.
 * A {@link ReentrantLock#tryLock()} keeps two callers from queueing behind each other: the nightly
 * schedule, the reset button and a workflow curl can all arrive at once, and the second one should
 * be told it is busy rather than wait to do the same work again.</p>
 *
 * <p>Both metadata rows are stamped with the same instant, because after a reset they are the same
 * fact: this data was seeded then, and that was the last reset.</p>
 */
@Service
public class ResetService {

    /** What happened: whether the data was replaced, when it last was, and why not if it was not. */
    public record Outcome(boolean performed, Instant lastResetAt, String reason) {
    }

    public static final String PERFORMED = "reset";
    public static final String BUSY = "busy";
    public static final String RECENT = "recent";

    private static final Logger log = LoggerFactory.getLogger(ResetService.class);

    private final JdbcOperations jdbc;
    private final AppMetadataRepository metadata;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    public ResetService(JdbcOperations jdbc, AppMetadataRepository metadata,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = jdbc;
        this.metadata = metadata;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Resets unless the data is younger than {@code minimumInterval}, which is what keeps the
     * nightly schedule and the workflow curl from resetting the demo twice in a row.
     */
    public Outcome resetIfStale(Duration minimumInterval, String actor) {
        Optional<Instant> last = lastResetAt();
        if (last.isPresent()
            && Duration.between(last.get(), clock.instant()).compareTo(minimumInterval) < 0) {
            return new Outcome(false, last.get(), RECENT);
        }
        return reset(actor);
    }

    /** Deletes and re-seeds now, or reports that somebody else already is. */
    public Outcome reset(String actor) {
        if (!lock.tryLock()) {
            return new Outcome(false, lastResetAt().orElse(null), BUSY);
        }
        try {
            Instant now = clock.instant();
            transactions.executeWithoutResult(status -> {
                SeedData seedData = new SeedData(jdbc, clock);
                seedData.deleteAll();
                seedData.seed();
                stamp(now);
            });
            log.info("demo data reset by {}", actor);
            return new Outcome(true, now, PERFORMED);
        } finally {
            lock.unlock();
        }
    }

    /** When the demo data was last replaced, which the about page and the footer print. */
    public Optional<Instant> lastResetAt() {
        return instantAt(AppMetadata.LAST_RESET_AT);
    }

    /** When the data now in the database was seeded; the same instant after a reset. */
    public Optional<Instant> seededAt() {
        return instantAt(AppMetadata.SEEDED_AT);
    }

    private void stamp(Instant now) {
        jdbc.update("update app_metadata set meta_value = ?, updated_at = ? "
            + "where meta_key in (?, ?)",
            now.toString(), now, AppMetadata.SEEDED_AT, AppMetadata.LAST_RESET_AT);
    }

    private Optional<Instant> instantAt(String key) {
        return metadata.findById(key).map(AppMetadata::getMetaValue).flatMap(ResetService::parse);
    }

    /**
     * A metadata row nobody but this application writes, read defensively anyway: an unreadable
     * timestamp should leave the about page saying it does not know, not fail the request.
     */
    private static Optional<Instant> parse(String value) {
        try {
            return Optional.of(Instant.parse(value));
        } catch (DateTimeParseException unreadable) {
            log.warn("app_metadata holds a value that is not an instant: {}", value);
            return Optional.empty();
        }
    }
}
