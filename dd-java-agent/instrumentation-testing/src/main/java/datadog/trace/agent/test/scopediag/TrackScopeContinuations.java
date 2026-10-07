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
  /** Set to {@code false} only for a proven incompatibility with the diagnostic itself. */
  boolean enabled() default true;

  /** Explains why the diagnostic is disabled. Required when {@link #enabled()} is false. */
  String reason() default "";
}
