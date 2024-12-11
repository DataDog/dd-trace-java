package datadog.test.agent.assertions;

import datadog.test.agent.AgentSpan;
import org.opentest4j.AssertionFailedError;

public final class AgentSpanMatcher {
  private boolean hasServiceName;
  private String serviceName;

  private AgentSpanMatcher() {}

  public static AgentSpanMatcher span() {
    return new AgentSpanMatcher();
  }

  public AgentSpanMatcher hasServiceName() {
    this.hasServiceName = true;
    return this;
  }

  public AgentSpanMatcher withServiceName(String serviceName) {
    this.serviceName = serviceName;
    return this;
  }

  public void assertSpan(AgentSpan span, AgentSpan previousSpan) {
    if (this.hasServiceName && span.service() == null) {
      throw new AssertionFailedError("Expected to have service name");
    }
    if (this.serviceName != null && !this.serviceName.equals(span.service())) {
      throw new AssertionFailedError("Invalid service name", this.serviceName, span.service());
    }
  }
}
