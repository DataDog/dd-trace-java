package datadog.trace.agent.test.scopediag;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Configures the default-on scope and continuation diagnostic for a test class or method. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Inherited
public @interface TrackScopeContinuations {
  /** Set to {@code false} only for a proven incompatibility with the diagnostic itself. */
  boolean enabled() default true;

  /** Explains why the diagnostic is disabled. Required when {@link #enabled()} is false. */
  String reason() default "";
}
