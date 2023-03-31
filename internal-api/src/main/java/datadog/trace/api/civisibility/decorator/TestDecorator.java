package datadog.trace.api.civisibility.decorator;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.nio.file.Path;

public interface TestDecorator {
  String TEST_TYPE = "test";

  AgentSpan afterStart(final AgentSpan span);

  CharSequence component();

  AgentSpan beforeFinish(final AgentSpan span);

  interface Factory {
    TestDecorator create(
        String component, String testFramework, String testFrameworkVersion, Path path);
  }
}
