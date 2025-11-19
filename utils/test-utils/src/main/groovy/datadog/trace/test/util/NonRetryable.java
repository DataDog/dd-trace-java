package datadog.trace.test.util;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;

/** Use this annotation for test cases that are not designed to be retryable (develocity). */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Tag("NonRetryable")
public @interface NonRetryable {
  /** Reason why the test is non-retryable (optional). */
  String value() default "";
}
