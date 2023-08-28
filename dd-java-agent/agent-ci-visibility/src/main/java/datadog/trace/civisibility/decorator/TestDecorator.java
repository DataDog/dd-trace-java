package datadog.trace.civisibility.decorator;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public interface TestDecorator {
  String TEST_TYPE = "test";

  AgentSpan afterStart(final AgentSpan span);

  CharSequence component();
}
