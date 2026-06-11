package datadog.trace.agent.test.scopediag;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opt-in marker that enables the scope-continuation leak diagnostic for an instrumentation test
 * class or method. When absent the diagnostic stays dormant, so existing tests are unaffected.
 *
 * <p>Honored by both the JUnit5 {@link ScopeDiagnosticsExtension} and the Groovy/Spock {@code
 * InstrumentationSpecification}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Inherited
public @interface TrackScopeContinuations {
  /** Fail the test if a never-resolved leak or double/invalid resolution is detected. */
  boolean failOnLeak() default false;

  /** Render the full text Gantt timeline to the log after the test. */
  boolean gantt() default true;

  /** Write the JSON report under {@code build/scope-diagnostics/}. */
  boolean json() default true;

  /** Write a Mermaid Gantt timeline ({@code .md}) under {@code build/scope-diagnostics/}. */
  boolean mermaid() default true;
}
