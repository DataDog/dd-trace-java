package datadog.trace.api;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Set this annotation to a test method so the dd-java-agent does not consider it for tracing. This
 * annotation must be only used in test framework instrumentation tests to avoid self-tracing of the
 * test itself.
 */
@Retention(RUNTIME)
@Target({TYPE, METHOD})
public @interface DisableTestTrace {

  /** The reason of why test trace has been disable for that test. */
  String reason() default "";
}
