package datadog.trace.agent.test.scopediag;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/**
 * Guards the {@code CANCELLED} sentinel that {@link ScopeContinuationProbe} duplicates from {@code
 * datadog.trace.core.scopemanager.ScopeContinuation}. If the production constant ever changes, the
 * probe's resolution detection would silently break — this test fails instead.
 */
class ScopeContinuationProbeTest {

  @Test
  void cancelledSentinelMatchesProduction() throws Exception {
    assertEquals(Integer.MIN_VALUE >> 1, ScopeContinuationProbe.CANCELLED);

    Class<?> scopeContinuation = Class.forName("datadog.trace.core.scopemanager.ScopeContinuation");
    Field cancelled = scopeContinuation.getDeclaredField("CANCELLED");
    cancelled.setAccessible(true);
    assertEquals(
        cancelled.getInt(null),
        ScopeContinuationProbe.CANCELLED,
        "ScopeContinuationProbe.CANCELLED is out of sync with ScopeContinuation.CANCELLED");
  }
}
