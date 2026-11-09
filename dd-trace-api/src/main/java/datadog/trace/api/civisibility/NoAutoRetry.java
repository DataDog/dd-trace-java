package datadog.trace.api.civisibility;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Excludes a test from Datadog Auto Test Retries, Early Flake Detection, and Attempt to Fix.
 *
 * <p>Explicit retries configured by the test framework or its plugins remain available. This
 * annotation does not override Datadog quarantine or disabling; use {@link
 * PreventFailureSuppression} to prevent those behaviors. Developer skips and Test Impact Analysis
 * selection retain their normal semantics.
 *
 * <p>Apply this annotation to a test method; parameterized invocations share the declaration.
 * Frameworks using native string tags or groups can use {@link #TAG} instead. The agent reports
 * {@code test.test_management.no_auto_retry: true} on each reported annotated execution, including
 * known skips, while retaining backend-assigned management tags. Removing the declaration restores
 * ordinary policy on the next execution and omits the annotation tag.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NoAutoRetry {
  /** Native test-framework tag equivalent to this annotation. */
  String TAG = "datadog_no_auto_retry";
}
