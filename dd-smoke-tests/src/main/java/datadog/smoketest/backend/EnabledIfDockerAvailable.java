package datadog.smoketest.backend;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Skips (loudly, as a JUnit conditional-execution result — not a failure) the annotated test class
 * or method when Docker is unavailable, so a developer not running smoke tests locally isn't
 * blocked (Q4/S7/G7). Apply it to tests that require a Testcontainers-managed test-agent {@code
 * .container()} backend.
 *
 * <p>Tests that select their backend from the environment via {@code TraceBackend.testAgent()}
 * don't need this annotation — that resolver already reuses an external CI agent when {@code
 * CI_AGENT_HOST} is set, and aborts loudly otherwise.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ExtendWith(DockerAvailableCondition.class)
public @interface EnabledIfDockerAvailable {}
