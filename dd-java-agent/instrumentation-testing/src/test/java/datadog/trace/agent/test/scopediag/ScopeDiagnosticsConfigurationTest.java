package datadog.trace.agent.test.scopediag;

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
      disabledChecks = {ScopeDiagnosticsCheck.LEAKED, ScopeDiagnosticsCheck.LATE_FINISH},
      reason = "these checks do not apply")
  private static class ExcludedChecks {}

  @TrackScopeContinuations(disabledChecks = {})
  private static class EmptyExclusions {}

  @TrackScopeContinuations
  private static class Defaults {}

  @TrackScopeContinuations(
      enabled = false,
      disabledChecks = ScopeDiagnosticsCheck.LEAKED,
      reason = "incompatible fixture")
  private static class DisabledWithExclusions {}

  @TrackScopeContinuations(disabledChecks = ScopeDiagnosticsCheck.LEAKED, reason = "  ")
  private static class UndocumentedExclusion {}

  @TrackScopeContinuations(disabledChecks = ScopeDiagnosticsCheck.LATE_FINISH)
  private static class UndocumentedAdvisoryExclusion {}

  @TrackScopeContinuations(
      disabledChecks = {
        ScopeDiagnosticsCheck.LEAKED,
        ScopeDiagnosticsCheck.DOUBLE_FINISH,
        ScopeDiagnosticsCheck.ACTIVATE_AFTER_RESOLVE,
        ScopeDiagnosticsCheck.NEVER_CLOSED
      },
      reason = "diagnostic investigation only")
  private static class AdvisoryOnly {}

  @TrackScopeContinuations(
      disabledChecks = {
        ScopeDiagnosticsCheck.LEAKED,
        ScopeDiagnosticsCheck.LATE_FINISH,
        ScopeDiagnosticsCheck.DOUBLE_FINISH,
        ScopeDiagnosticsCheck.ACTIVATE_AFTER_RESOLVE,
        ScopeDiagnosticsCheck.CLOSE_WRONG_THREAD,
        ScopeDiagnosticsCheck.NEVER_CLOSED
      },
      reason = "diagnostic investigation only")
  private static class AllExcluded {}

  @Test
  void diagnosticsAreEnabledByDefault() {
    assertTrue(ScopeDiagnostics.isEnabled(null));
  }

  @Test
  void defaultSelectionIncludesEveryEnumValue() {
    TrackScopeContinuations config = Defaults.class.getAnnotation(TrackScopeContinuations.class);

    assertEquals(
        EnumSet.allOf(ScopeDiagnosticsCheck.class), ScopeDiagnostics.enabledChecks(config));
  }

  @Test
  void excludedChecksAreRemovedFromDefaults() {
    TrackScopeContinuations config =
        ExcludedChecks.class.getAnnotation(TrackScopeContinuations.class);
    EnumSet<ScopeDiagnosticsCheck> expected = EnumSet.allOf(ScopeDiagnosticsCheck.class);
    expected.remove(ScopeDiagnosticsCheck.LEAKED);
    expected.remove(ScopeDiagnosticsCheck.LATE_FINISH);

    assertEquals(expected, ScopeDiagnostics.enabledChecks(config));
  }

  @Test
  void emptyExclusionsKeepEveryCheckEnabled() {
    TrackScopeContinuations config =
        EmptyExclusions.class.getAnnotation(TrackScopeContinuations.class);

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
  void fullOptOutCannotSupplyDisabledChecks() {
    assertInvalid(DisabledWithExclusions.class);
  }

  @Test
  void undocumentedExclusionIsRejected() {
    assertInvalid(UndocumentedExclusion.class);
  }

  @Test
  void excludingAnAdvisoryCheckStillRequiresAReason() {
    assertInvalid(UndocumentedAdvisoryExclusion.class);
  }

  @Test
  void excludingAllEnforcedChecksKeepsRecordingEnabled() {
    TrackScopeContinuations config =
        AdvisoryOnly.class.getAnnotation(TrackScopeContinuations.class);

    assertTrue(ScopeDiagnostics.isEnabled(config));
    assertTrue(
        ScopeDiagnosticsReport.enforcedChecks(ScopeDiagnostics.enabledChecks(config)).isEmpty());
  }

  @Test
  void excludingEveryCheckKeepsRecordingEnabled() {
    TrackScopeContinuations config = AllExcluded.class.getAnnotation(TrackScopeContinuations.class);

    assertTrue(ScopeDiagnostics.isEnabled(config));
    assertTrue(ScopeDiagnostics.enabledChecks(config).isEmpty());
  }

  private static void assertInvalid(Class<?> fixture) {
    TrackScopeContinuations config = fixture.getAnnotation(TrackScopeContinuations.class);
    assertThrows(IllegalArgumentException.class, () -> ScopeDiagnostics.isEnabled(config));
  }
}
