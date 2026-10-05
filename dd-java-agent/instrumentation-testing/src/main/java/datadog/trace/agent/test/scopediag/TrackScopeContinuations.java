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
   * Selects whether {@link #checks()} is the complete set of active checks ({@code true}) or the
   * checks to remove from the default full set ({@code false}).
   */
  boolean enabled() default true;

  /**
   * Checks selected by {@link #enabled()}. The default lists every available check; a configuration
   * test guards that invariant as new checks are added.
   */
  ScopeDiagnosticsCheck[] checks() default {
    ScopeDiagnosticsCheck.LEAKED,
    ScopeDiagnosticsCheck.LATE_FINISH,
    ScopeDiagnosticsCheck.DOUBLE_FINISH,
    ScopeDiagnosticsCheck.ACTIVATE_AFTER_RESOLVE,
    ScopeDiagnosticsCheck.CLOSE_WRONG_THREAD,
    ScopeDiagnosticsCheck.NEVER_CLOSED
  };

  /** Explains any reduction from the default full check set. */
  String reason() default "";
}
