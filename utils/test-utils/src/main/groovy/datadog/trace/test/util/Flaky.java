package datadog.trace.test.util;

import groovy.lang.Closure;
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

  /**
   * Closure with a predicate to test at runtime if the actual spec is flaky (e.g. check the JVM
   * vendor)
   */
  Class<? extends Closure<Boolean>> condition() default True.class;

  class True extends Closure<Boolean> {

    public True(final Object owner, final Object thisObject) {
      super(owner, thisObject);
    }

    public Boolean call() {
      return true;
    }
  }
}
