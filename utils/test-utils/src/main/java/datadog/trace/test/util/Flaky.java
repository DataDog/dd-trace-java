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
   * Names of the concrete classes where this test is flaky, typically subclasses that inherit the
   * annotated method. Each entry must exactly match the executing class's simple or canonical name;
   * subclasses and nested classes must be listed separately.
   */
  String[] suites() default {};

  /**
   * Reference in {@code fully.qualified.Class#method} format to a static, no-argument boolean
   * method that determines whether the test is flaky in JUnit.
   */
  String conditionMethod() default "";

  /**
   * Predicate that determines whether the test is flaky in Spock. Groovy closures are also
   * supported.
   */
  Class<? extends Predicate<String>> condition() default True.class;

  class True implements Predicate<String> {

    @Override
    public boolean test(final String spec) {
      return true;
    }
  }
}
