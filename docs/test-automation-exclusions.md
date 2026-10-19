# Test automation exclusions

Use the `dd-trace-api` artifact to declare test-local Datadog policy exclusions.

```java
import datadog.trace.api.civisibility.NoAutoRetry;
import datadog.trace.api.civisibility.PreventFailureSuppression;
import org.junit.Test;

class CriticalTest {
  @Test
  @NoAutoRetry
  @PreventFailureSuppression
  public void criticalBehavior() {
    // Ordinary assertions retain their normal meaning.
  }
}
```

`@PreventFailureSuppression` prevents Datadog disabling and quarantine from
controlling the test. `@NoAutoRetry` excludes Datadog Auto Test Retries, Early
Flake Detection and Attempt to Fix. Explicit framework retries, developer skips,
and Test Impact Analysis selection retain their existing behavior. Apply the
annotations to the test method; parameterized invocations share that declaration.

The agent reports true-only `test.test_management.no_auto_retry` and
`test.test_management.prevent_failure_suppression` tags on each reported
execution, including skips. Backend assignment tags remain visible even when
the assigned behavior is excluded. Removing an annotation restores ordinary
policy on the next execution.
