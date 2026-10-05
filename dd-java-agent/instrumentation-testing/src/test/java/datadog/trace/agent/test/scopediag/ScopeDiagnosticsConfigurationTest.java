package datadog.trace.agent.test.scopediag;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class ScopeDiagnosticsConfigurationTest {

  @TrackScopeContinuations(enabled = false)
  private static class UndocumentedOptOut {}

  @TrackScopeContinuations(enabled = false, reason = "incompatible synthetic tracer")
  private static class DocumentedOptOut {}

  @TrackScopeContinuations(
      checks = {ScopeDiagnosticsCheck.LEAKED, ScopeDiagnosticsCheck.LATE_FINISH},
      reason = "only these checks apply")
  private static class CheckWhitelist {}

  @TrackScopeContinuations(
      enabled = false,
      checks = {ScopeDiagnosticsCheck.LEAKED, ScopeDiagnosticsCheck.LATE_FINISH},
      reason = "these checks do not apply")
  private static class CheckBlacklist {}

  @TrackScopeContinuations(
      enabled = false,
      checks = {})
  private static class EmptyBlacklist {}

  @TrackScopeContinuations(checks = ScopeDiagnosticsCheck.LEAKED)
  private static class UndocumentedWhitelist {}

  @TrackScopeContinuations
  private static class Defaults {}

  @Test
  void diagnosticsAreEnabledByDefault() {
    assertTrue(ScopeDiagnostics.isEnabled(null));
  }

  @Test
  void defaultChecksIncludeEveryEnumValue() {
    TrackScopeContinuations config = Defaults.class.getAnnotation(TrackScopeContinuations.class);

    assertEquals(
        EnumSet.allOf(ScopeDiagnosticsCheck.class), EnumSet.copyOf(asList(config.checks())));
  }

  @Test
  void enabledChecksAreAWhitelist() {
    TrackScopeContinuations config =
        CheckWhitelist.class.getAnnotation(TrackScopeContinuations.class);

    assertEquals(
        EnumSet.of(ScopeDiagnosticsCheck.LEAKED, ScopeDiagnosticsCheck.LATE_FINISH),
        ScopeDiagnostics.enabledChecks(config));
  }

  @Test
  void disabledChecksAreABlacklist() {
    TrackScopeContinuations config =
        CheckBlacklist.class.getAnnotation(TrackScopeContinuations.class);
    EnumSet<ScopeDiagnosticsCheck> expected = EnumSet.allOf(ScopeDiagnosticsCheck.class);
    expected.remove(ScopeDiagnosticsCheck.LEAKED);
    expected.remove(ScopeDiagnosticsCheck.LATE_FINISH);

    assertEquals(expected, ScopeDiagnostics.enabledChecks(config));
  }

  @Test
  void emptyBlacklistKeepsEveryCheckEnabled() {
    TrackScopeContinuations config =
        EmptyBlacklist.class.getAnnotation(TrackScopeContinuations.class);

    assertEquals(
        EnumSet.allOf(ScopeDiagnosticsCheck.class), ScopeDiagnostics.enabledChecks(config));
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

  @Test
  void undocumentedWhitelistIsRejected() {
    TrackScopeContinuations config =
        UndocumentedWhitelist.class.getAnnotation(TrackScopeContinuations.class);

    assertThrows(IllegalArgumentException.class, () -> ScopeDiagnostics.isEnabled(config));
  }
}
