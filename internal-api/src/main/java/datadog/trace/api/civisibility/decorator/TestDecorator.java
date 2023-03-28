package datadog.trace.api.civisibility.decorator;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.lang.reflect.Method;
import java.util.Collection;
import javax.annotation.Nullable;

public interface TestDecorator {
  String TEST_TYPE = "test";

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

  void afterTestSuiteStart(
      AgentSpan span,
      String testSuiteName,
      @Nullable Class<?> testClass,
      @Nullable String version,
      @Nullable Collection<String> categories);

  void afterTestStart(
      AgentSpan span,
      String testSuiteName,
      String testName,
      @Nullable String testParameters,
      @Nullable String version,
      @Nullable Class<?> testClass,
      @Nullable Method testMethod,
      @Nullable Collection<String> categories);

  CharSequence component();

  AgentSpan beforeFinish(final AgentSpan span);
}
