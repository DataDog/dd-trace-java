package datadog.trace.api.civisibility.events;

import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.telemetry.tag.SkipReason;
import org.junit.jupiter.api.Test;

class TestEventsHandlerTest {
  @Test
  void declarationAwareSkipReasonPreservesLegacyImplementations() {
    TestEventsHandler<?, ?> handler = mock(TestEventsHandler.class, CALLS_REAL_METHODS);
    TestIdentifier test = new TestIdentifier("suite", "test", null);
    when(handler.skipReason(test)).thenReturn(SkipReason.ITR);

    assertEquals(
        SkipReason.ITR, handler.skipReason(test, TestSourceData.UNKNOWN, singletonList("tag")));
    verify(handler).skipReason(test);
  }
}
