package datadog.trace.junit.utils.config;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Declares a configuration override for a test. Can be placed on a test class (applies to all
 * tests) or on individual test methods.
 *
 * <p>By default, injects a system property with the {@code dd.} prefix. Use {@code env = true} for
 * environment variables (prefix {@code DD_}).
 *
 * <p>Examples:
 *
 * <pre>{@code
 * @WithConfig(key = "service", value = "my_service")
 * @WithConfig(key = "trace.resolver.enabled", value = "false")
 * class MyTest extends DDJavaSpecification {
 *
 *   @Test
 *   @WithConfig(key = "AGENT_HOST", value = "localhost", env = true)
 *   void testWithEnv() { ... }
 * }
 * }</pre>
 */
@Retention(RUNTIME)
@Target({TYPE, METHOD})
@Repeatable(WithConfigs.class)
@ExtendWith(WithConfigExtension.class)
public @interface WithConfig {
  /**
   * Config key (e.g. {@code "trace.resolver.enabled"}). The {@code dd.}/{@code DD_} prefix is
   * auto-added unless {@link #addPrefix()} is {@code false}.
   */
  String key();

  /** Config value. */
  String value();

  /** If {@code true}, sets an environment variable instead of a system property. */
  boolean env() default false;

  /** If {@code false}, the key is used as-is without adding the {@code dd.}/{@code DD_} prefix. */
  boolean addPrefix() default true;
}
