package datadog.trace.api.civisibility.decorator;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import javax.annotation.Nullable;

public interface TestDecorator {
  String TEST_TYPE = "test";

  AgentSpan afterStart(final AgentSpan span);

  void afterTestSessionStart(
      AgentSpan span,
      String projectName,
      String startCommand,
      String buildSystemName,
      String buildSystemVersion);

  void afterTestModuleStart(
      AgentSpan span,
      @Nullable String moduleName,
      @Nullable String version,
      @Nullable String startCommand);

  CharSequence component();

  AgentSpan beforeFinish(final AgentSpan span);
}
