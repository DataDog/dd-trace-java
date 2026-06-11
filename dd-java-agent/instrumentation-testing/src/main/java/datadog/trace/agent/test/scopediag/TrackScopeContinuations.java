package datadog.trace.agent.test.scopediag;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Overrides the scope-continuation diagnostic defaults for an instrumentation test class or method.
 * Diagnostics run for every instrumentation test unless explicitly disabled.
 *
 * <p>Honored by both the JUnit5 {@link ScopeDiagnosticsExtension} and the Groovy/Spock {@code
 * InstrumentationSpecification}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Inherited
public @interface TrackScopeContinuations {
  /** Set to {@code false} only for a proven incompatibility with the diagnostic itself. */
  boolean enabled() default true;

  /** Required when disabling diagnostics. Explain the incompatibility, preferably with an issue. */
  String reason() default "";
}
