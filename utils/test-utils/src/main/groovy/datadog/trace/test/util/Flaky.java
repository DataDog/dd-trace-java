package datadog.trace.test.util;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;

/**
 * Use this annotation for suites or test cases that are flaky. When running in CI, these will be
 * segregated to a separate job.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Tag("flaky")
public @interface Flaky {
  /** Reason why the test is flaky (optional). */
  String value() default "";

  /**
   * Fully qualified name of the test suite classes where this test is flaky. Only required when the
   * test is flaky only when run in a subclass.
   */
  String[] suites() default {};
}
