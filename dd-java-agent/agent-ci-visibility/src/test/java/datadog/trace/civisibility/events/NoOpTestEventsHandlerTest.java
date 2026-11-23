package datadog.trace.civisibility.events;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.api.civisibility.NoAutoRetry;
import datadog.trace.api.civisibility.PreventFailureSuppression;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestSourceData;
import org.junit.jupiter.api.Test;

class NoOpTestEventsHandlerTest {
  @Test
  void preservesNoOpSkipBehaviorWithDeclarationMetadata() {
    NoOpTestEventsHandler<Object, Object> handler = new NoOpTestEventsHandler<>();
    TestIdentifier test = new TestIdentifier("suite", "test", null);

    assertNull(handler.skipReason(test));
    assertNull(handler.skipReason(test, TestSourceData.UNKNOWN, emptyList()));
    assertNull(handler.skipReason(test, TestSourceData.UNKNOWN, singletonList(NoAutoRetry.TAG)));
    assertNull(
        handler.skipReason(
            test, TestSourceData.UNKNOWN, singletonList(PreventFailureSuppression.TAG)));
  }
}
