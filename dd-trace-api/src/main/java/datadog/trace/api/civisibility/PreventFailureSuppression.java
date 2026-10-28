package datadog.trace.api.civisibility;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Prevents Datadog from disabling a test or suppressing its terminal failure through quarantine.
 *
 * <p>A selected test retains its normal assertion and expected-failure semantics: a passing test
 * remains passing, and an ordinary terminal failure remains blocking. This annotation does not
 * prohibit retries; combine it with {@link NoAutoRetry} to exclude Datadog automatic retries.
 * Developer skips and Test Impact Analysis selection retain their normal semantics.
 *
 * <p>Apply this annotation to a test method; parameterized invocations share the declaration.
 * Frameworks using native string tags or groups can use {@link #TAG} instead. The agent reports
 * {@code test.test_management.prevent_failure_suppression: true} on each reported annotated
 * execution, including known skips, while retaining backend-assigned management tags. Removing the
 * declaration restores ordinary policy on the next execution and omits the annotation tag.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PreventFailureSuppression {
  /** Native test-framework tag equivalent to this annotation. */
  String TAG = "datadog_prevent_failure_suppression";
}
