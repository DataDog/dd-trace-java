package datadog.trace.bootstrap.instrumentation.civisibility;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;

class SpanTestContext extends AbstractTestContext implements TestContext {
  private final AgentSpan span;
  private final Long parentId;

  SpanTestContext(AgentSpan span) {
    this(span, null);
  }

  SpanTestContext(AgentSpan span, Long parentId) {
    this.span = span;
    this.parentId = parentId;
  }

  @Override
  public long getId() {
    return span.getSpanId();
  }

  @Override
  public Long getParentId() {
    return parentId;
  }

  @Override
  public String getStatus() {
    String status = (String) span.getTag(Tags.TEST_STATUS);
    if (status != null) {
      // status was set explicitly for container span
      // (e.g. set up or tear down have failed)
      // in this case we ignore children statuses
      return status;
    } else {
      return super.getStatus();
    }
  }

  @Override
  public boolean isLocalToCurrentProcess() {
    return true;
  }
}
