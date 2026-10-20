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

## JUnit 5

Use the same method annotations with `org.junit.jupiter.api.Test`,
`ParameterizedTest`, repeated tests and test templates. Every generated
invocation resolves the declaration on its source method. Native JUnit tags can
also reference `TestAutomationExclusions` constants.

## TestNG

Apply the public annotations to `org.testng.annotations.Test` methods, including
data providers. TestNG groups can also reference `TestAutomationExclusions`
constants. User-supplied retry analyzers retain their existing behavior.

## Spock

Apply the public annotations directly to a Spock feature method. They apply
to every data-driven iteration. Spec-level skip aggregation inspects the
feature declaration before deciding whether the whole spec can be skipped.

## Cucumber

For either the JUnit4 or JUnit Platform Cucumber runner, tag a Scenario or
Scenario Outline with `@datadog_no_auto_retry` and/or
`@datadog_prevent_failure_suppression`. These names correspond to the public
`TestAutomationExclusions` constants. Tags apply to every outline example.

## Karate

Tag the Scenario or Scenario Outline with `@datadog_no_auto_retry` and/or
`@datadog_prevent_failure_suppression` (the names from the public
`TestAutomationExclusions` constants). Both Karate 1.x and 2.x resolve these
scenario tags before Datadog skipping and retry policy selection.

## ScalaTest

Declare ScalaTest `Tag` instances from `TestAutomationExclusions.NO_AUTO_RETRY`
and `PREVENT_FAILURE_SUPPRESSION`, then pass them to the test declaration.
Native ignored tests remain ignored and carry their known exclusion tags.

## MUnit

Apply native MUnit tags using the public constants:

```scala
import datadog.trace.api.civisibility.TestAutomationExclusions

test("critical behavior"
  .tag(new munit.Tag(TestAutomationExclusions.NO_AUTO_RETRY))
  .tag(new munit.Tag(TestAutomationExclusions.PREVENT_FAILURE_SUPPRESSION))) {
  assert(false)
}
```

MUnit's existing category bridge passes these declarations to the shared policy
and reporter. Ignored tests retain their known tags. MUnit currently has no
Datadog disabling capability; exclusions do not enable unsupported automation.

## Weaver

Use `TestName.tagged(TestAutomationExclusions.NO_AUTO_RETRY)` and
`TestName.tagged(TestAutomationExclusions.PREVENT_FAILURE_SUPPRESSION)` on
native Weaver test declarations. The agent snapshots declaration tags at each
suite execution and reports them with native failure and skip outcomes.

Weaver currently supports none of Datadog ATR, EFD, Attempt to Fix, quarantine
or disabling. The metadata bridge does not enable those capabilities.
