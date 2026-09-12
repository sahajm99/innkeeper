package io.github.sahajm99.innkeeper.seed;

import java.time.Clock;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;

/**
 * Applies the demo data as a versioned migration, so a fresh database comes up populated and
 * {@code flyway_schema_history} records that it did.
 *
 * <p>It is a Java migration rather than a SQL one because the dates are relative to the day the
 * demo is deployed and the invoices are computed by the domain, and it is a Spring bean - which
 * Spring Boot hands to Flyway on its own - so it can take the application {@link Clock} and share
 * {@link SeedData} with the nightly reset. The class deliberately sits outside
 * {@code db/migration}: Flyway also scans that location, and it would then find this migration
 * twice.</p>
 */
@Component
public class V2__seed_data extends BaseJavaMigration {

    private final Clock clock;

    public V2__seed_data(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void migrate(Context context) {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(context.getConnection(), true));
        new SeedData(jdbc, clock).seed();
    }
}
