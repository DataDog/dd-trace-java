package datadog.trace.agent.test.scopediag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary.Failure;

class ScopeDiagnosticsSpockExtensionTest {
  private static final String FAILING_FIXTURE =
      "datadog.trace.agent.test.scopediag.FailingScopeDiagnosticsSpockFixture";
  private static final String RUN_FAILING_FIXTURE = "scope.diagnostics.run.failing.fixture";

  @Test
  void preservesSuiteSetupFailureAndSuppressesDiagnosticFailure() {
    LauncherDiscoveryRequest discoveryRequest =
        request().selectors(selectClass(FAILING_FIXTURE)).build();
    SummaryGeneratingListener listener = new SummaryGeneratingListener();

    String previous = System.setProperty(RUN_FAILING_FIXTURE, "true");
    try {
      LauncherFactory.create().execute(discoveryRequest, listener);
    } finally {
      if (previous == null) {
        System.clearProperty(RUN_FAILING_FIXTURE);
      } else {
        System.setProperty(RUN_FAILING_FIXTURE, previous);
      }
    }

    List<Failure> failures = listener.getSummary().getFailures();
    assertEquals(1, failures.size());
    Throwable setupFailure = failures.get(0).getException();
    assertInstanceOf(IllegalStateException.class, setupFailure);
    assertEquals("suite setup failed", setupFailure.getMessage());
    assertEquals(1, setupFailure.getSuppressed().length);
    assertInstanceOf(AssertionError.class, setupFailure.getSuppressed()[0]);
    assertEquals("scope continuation leaked", setupFailure.getSuppressed()[0].getMessage());
  }
}
