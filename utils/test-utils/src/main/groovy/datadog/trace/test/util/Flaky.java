package datadog.trace.test.util;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.function.Predicate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Use this annotation for suites or test cases that are flaky. When running in CI, these will be
 * split to a separate job. Apply this annotation instead of {@code @Tag("flaky")} directly.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Tag("flaky")
@ExtendWith(FlakyJUnitExtension.class)
public @interface Flaky {
  /** Reason why the test is flaky (optional). */
  String value() default "";

  /**
   * Names of the test suite classes where this test is flaky, typically subclasses that inherit the
   * test. Spock uses simple class names; JUnit accepts simple or fully qualified class names.
   */
  String[] suites() default {};

  /**
   * Predicate class with a no-argument constructor that determines whether the test is flaky (e.g.
   * check the JVM vendor). JUnit passes the concrete test class's simple name. Spock also supports
   * Groovy closures.
   */
  Class<? extends Predicate<String>> condition() default True.class;

  class True implements Predicate<String> {

    @Override
    public boolean test(final String spec) {
      return true;
    }
  }
}
