package datadog.trace.agent.test.scopediag;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;
import org.spockframework.runtime.extension.ExtensionAnnotation;

/**
 * Enables and configures scope and continuation diagnostics for a JUnit or Spock test. Class-level
 * configuration also applies to suite setup and cleanup.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Inherited
@ExtendWith(ScopeDiagnosticsExtension.class)
@ExtensionAnnotation(ScopeDiagnosticsSpockExtension.class)
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
