package io.github.sahajm99.innkeeper.service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * What the about page prints: which build this is, what it is talking to, and how old the demo data
 * is.
 *
 * <p>Everything here is optional at runtime. A jar built outside a git checkout has no
 * git.properties and a container built without the build-info goal has no build-info.properties, so
 * both come through an {@link ObjectProvider} and fall back - first to the commit the host puts in
 * the environment, then to "unknown". A page that says "unknown" is useful; one that fails to
 * render is not.</p>
 */
@Service
public class AboutService {

    /** One deployment, described. The two instants are absent before the first seed. */
    public record Info(String commit, String commitTime, String buildTime, String database,
        String profile, Optional<Instant> seededAt, Optional<Instant> lastResetAt,
        String poolStats) {
    }

    public static final String UNKNOWN = "unknown";

    /** Hosts that hand the commit to the process rather than to the build. */
    private static final String[] COMMIT_ENVIRONMENT = {"RENDER_GIT_COMMIT", "GIT_COMMIT"};

    private static final String DEFAULT_PROFILE = "default";

    private static final Logger log = LoggerFactory.getLogger(AboutService.class);

    private final ObjectProvider<GitProperties> git;
    private final ObjectProvider<BuildProperties> build;
    private final DataSource dataSource;
    private final Environment environment;
    private final ResetService resetService;

    /** The product and version of a database do not change while the process runs; read once. */
    private volatile String database;

    public AboutService(ObjectProvider<GitProperties> git, ObjectProvider<BuildProperties> build,
            DataSource dataSource, Environment environment, ResetService resetService) {
        this.git = git;
        this.build = build;
        this.dataSource = dataSource;
        this.environment = environment;
        this.resetService = resetService;
    }

    public Info info() {
        return new Info(commit(), commitTime(), buildTime(), database(), profile(),
            resetService.seededAt(), resetService.lastResetAt(), poolStats());
    }

    private String commit() {
        GitProperties properties = git.getIfAvailable();
        if (properties != null && properties.getShortCommitId() != null) {
            return properties.getShortCommitId();
        }
        for (String variable : COMMIT_ENVIRONMENT) {
            String value = System.getenv(variable);
            if (value != null && !value.isBlank()) {
                return value.length() > 7 ? value.substring(0, 7) : value;
            }
        }
        return UNKNOWN;
    }

    private String commitTime() {
        GitProperties properties = git.getIfAvailable();
        return properties == null || properties.getCommitTime() == null
            ? UNKNOWN
            : properties.getCommitTime().toString();
    }

    private String buildTime() {
        BuildProperties properties = build.getIfAvailable();
        return properties == null || properties.getTime() == null
            ? UNKNOWN
            : properties.getTime().toString();
    }

    /** "PostgreSQL 16.4" or "H2 2.3.232": the product, and the version without its build date. */
    private String database() {
        String known = database;
        if (known != null) {
            return known;
        }
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            known = metaData.getDatabaseProductName() + " " + shortVersion(metaData);
        } catch (SQLException unreadable) {
            log.warn("Could not read the database product: {}", unreadable.getMessage());
            known = UNKNOWN;
        }
        database = known;
        return known;
    }

    private String shortVersion(DatabaseMetaData metaData) throws SQLException {
        String version = metaData.getDatabaseProductVersion();
        if (version == null || version.isBlank()) {
            return UNKNOWN;
        }
        return version.split(" ")[0];
    }

    private String profile() {
        String[] active = environment.getActiveProfiles();
        return active.length == 0 ? DEFAULT_PROFILE : String.join(", ", active);
    }

    /** Three numbers that say whether a free-tier database is being asked for too much at once. */
    private String poolStats() {
        if (dataSource instanceof HikariDataSource hikari) {
            HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
            if (pool != null) {
                return pool.getActiveConnections() + " active, " + pool.getIdleConnections()
                    + " idle, " + pool.getTotalConnections() + " total";
            }
        }
        return UNKNOWN;
    }
}
