package datadog.trace.agent.test.scopediag;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ScopeDiagnosticsConfigurationTest {

  @TrackScopeContinuations(enabled = false)
  private static class UndocumentedOptOut {}

  @TrackScopeContinuations(enabled = false, reason = "incompatible synthetic tracer")
  private static class DocumentedOptOut {}

  @Test
  void diagnosticsAreEnabledByDefault() {
    assertTrue(ScopeDiagnostics.isEnabled(null));
  }

  @Test
  void documentedOptOutDisablesDiagnostics() {
    assertFalse(
        ScopeDiagnostics.isEnabled(
            DocumentedOptOut.class.getAnnotation(TrackScopeContinuations.class)));
  }

  @Test
  void undocumentedOptOutIsRejected() {
    TrackScopeContinuations config =
        UndocumentedOptOut.class.getAnnotation(TrackScopeContinuations.class);

    assertThrows(IllegalArgumentException.class, () -> ScopeDiagnostics.isEnabled(config));
  }
}
