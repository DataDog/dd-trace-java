package datadog.trace.test.util;

import groovy.lang.Closure;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.math.BigDecimal;
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

    public True(final Object owner) {
      super(owner);
    }

    public Boolean call() {
      return true;
    }
  }

  class IBM8 extends Closure<Boolean> {

    private static final String VENDOR_STRING = "IBM";
    private static final BigDecimal VERSION = new BigDecimal("1.8");

    public IBM8(Object owner, Object thisObject) {
      super(owner, thisObject);
    }

    @Override
    public Boolean call() {
      final String vendor = System.getProperty("java.vendor", "");
      if (!vendor.contains(VENDOR_STRING)) {
        return false;
      }
      final BigDecimal version =
          new BigDecimal(System.getProperty("java.specification.version", "-1"));
      return version.equals(VERSION);
    }
  }
}
