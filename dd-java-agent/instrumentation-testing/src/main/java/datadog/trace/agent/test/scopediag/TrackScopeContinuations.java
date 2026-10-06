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
  /**
   * Enables recording and diagnostics. Disabling requires a reason and an empty {@link
   * #disabledChecks()} list.
   */
  boolean enabled() default true;

  /** Checks excluded from enforcement while recording remains enabled. Requires a reason. */
  ScopeDiagnosticsCheck[] disabledChecks() default {};

  /** Required when recording is disabled or {@link #disabledChecks()} is nonempty. */
  String reason() default "";
}
