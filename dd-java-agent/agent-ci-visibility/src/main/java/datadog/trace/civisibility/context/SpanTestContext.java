package datadog.trace.civisibility.context;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;

public class SpanTestContext extends AbstractTestContext implements TestContext {
  private final AgentSpan span;
  private final TestContext parent;

  public SpanTestContext(AgentSpan span, TestContext parent) {
    this.span = span;
    this.parent = parent;
  }

  @Override
  public Long getId() {
    return span.getSpanId();
  }

  @Override
  public Long getParentId() {
    return parent != null ? parent.getId() : null;
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
  public AgentSpan getSpan() {
    return span;
  }

  @Override
  public void reportChildTag(String key, Object value) {
    span.setTag(key, value);
    if (parent != null) {
      parent.reportChildTag(key, value);
    }
  }
}
