package datadog.trace.civisibility.context;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;

public class SpanTestContext extends AbstractTestContext implements TestContext {
  private final AgentSpan span;
  private final Long parentId;

  public SpanTestContext(AgentSpan span, Long parentId) {
    this.span = span;
    this.parentId = parentId;
  }

  @Override
  public Long getId() {
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

  @Override
  public AgentSpan getSpan() {
    return span;
  }
}
