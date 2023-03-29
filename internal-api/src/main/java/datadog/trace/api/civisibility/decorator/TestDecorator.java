package datadog.trace.api.civisibility.decorator;

import datadog.trace.api.civisibility.codeowners.Codeowners;
import datadog.trace.api.civisibility.source.SourcePathResolver;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Collection;
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

  void afterTestSuiteStart(
      AgentSpan span,
      String testSuiteName,
      @Nullable Class<?> testClass,
      @Nullable String version,
      @Nullable Collection<String> categories);

  CharSequence component();

  AgentSpan beforeFinish(final AgentSpan span);

  // FIXME remove the getters below, this should be done differently
  String getModulePath();

  SourcePathResolver getSourcePathResolver();

  Codeowners getCodeowners();
}
