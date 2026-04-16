package datadog.trace.junit.utils.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
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
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
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
