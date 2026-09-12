package io.github.sahajm99.innkeeper.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

/**
 * Skips the container-backed tests on a machine without Docker, so that {@code mvn test} stays
 * green on a laptop, while CI sets {@code -Dinnkeeper.requireDocker=true} and gets a loud failure
 * if the daemon is missing there.
 */
public class DockerAvailable implements ExecutionCondition {

    /** Set to {@code true} in CI so a missing daemon fails the build instead of skipping it. */
    public static final String REQUIRE_DOCKER_PROPERTY = "innkeeper.requireDocker";

    /** The message reported for every skipped container test. */
    public static final String DISABLED_REASON = "Docker is not available; skipping PostgreSQL tests";

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        if (dockerIsAvailable()) {
            return ConditionEvaluationResult.enabled("Docker is available");
        }
        if (Boolean.getBoolean(REQUIRE_DOCKER_PROPERTY)) {
            return ConditionEvaluationResult.enabled(
                REQUIRE_DOCKER_PROPERTY + " is true, so the missing daemon must fail the test");
        }
        return ConditionEvaluationResult.disabled(DISABLED_REASON);
    }

    /** Probing the daemon throws rather than returning false when the socket is not there at all. */
    private boolean dockerIsAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException | LinkageError probeFailed) {
            return false;
        }
    }
}

/**
 * Runs the annotated test only when Docker is available. Package-private on purpose: tests outside
 * this package pick it up by extending {@link AbstractPostgresTest}.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ExtendWith(DockerAvailable.class)
@interface EnabledIfDockerAvailable {
}
