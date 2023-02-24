package datadog.trace.bootstrap.instrumentation.civisibility;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.concurrent.atomic.LongAdder;

class TestContext {
  private final AgentSpan span;
  private final LongAdder childrenPassed = new LongAdder();
  private final LongAdder childrenFailed = new LongAdder();

  TestContext(AgentSpan span) {
    this.span = span;
  }

  long getId() {
    return span.getSpanId();
  }

  void reportChildStatus(String status) {
    switch (status) {
      case TestEventsHandler.TEST_PASS:
        childrenPassed.increment();
        break;
      case TestEventsHandler.TEST_FAIL:
        childrenFailed.increment();
        break;
      default:
        break;
    }
  }

  String getStatus() {
    String status = (String) span.getTag(Tags.TEST_STATUS);
    if (status != null) {
      // status was set explicitly for container span
      // (e.g. set up or tear down have failed)
      // in this case we ignore children statuses
      return status;
    }
    if (childrenFailed.sum() > 0) {
      return TestEventsHandler.TEST_FAIL;
    } else if (childrenPassed.sum() > 0) {
      return TestEventsHandler.TEST_PASS;
    } else {
      return TestEventsHandler.TEST_SKIP;
    }
  }
}
