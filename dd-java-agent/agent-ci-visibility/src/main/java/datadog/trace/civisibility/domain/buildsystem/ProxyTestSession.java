package datadog.trace.civisibility.domain.buildsystem;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.coverage.CoverageStore;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.config.ExecutionSettings;
import datadog.trace.civisibility.coverage.percentage.child.ChildProcessCoverageReporter;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.domain.TestFrameworkModule;
import datadog.trace.civisibility.domain.TestFrameworkSession;
import datadog.trace.civisibility.ipc.SignalClient;
import datadog.trace.civisibility.source.MethodLinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import javax.annotation.Nullable;

/**
 * Test session implementation that is used by test framework instrumentations in those cases when
 * the build system IS instrumented: since build system instrumentation manages the session span,
 * this class does not do it.
 */
public class ProxyTestSession implements TestFrameworkSession {

  private final long parentProcessSessionId;
  private final long parentProcessModuleId;
  private final Config config;
  private final CiVisibilityMetricCollector metricCollector;
  private final TestDecorator testDecorator;
  private final SourcePathResolver sourcePathResolver;
  private final Codeowners codeowners;
  private final MethodLinesResolver methodLinesResolver;
  private final CoverageStore.Factory coverageStoreFactory;
  private final ChildProcessCoverageReporter childProcessCoverageReporter;
  private final SignalClient.Factory signalClientFactory;
  private final ExecutionSettings executionSettings;

  public ProxyTestSession(
      long parentProcessSessionId,
      long parentProcessModuleId,
      Config config,
      CiVisibilityMetricCollector metricCollector,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver,
      Codeowners codeowners,
      MethodLinesResolver methodLinesResolver,
      CoverageStore.Factory coverageStoreFactory,
      ChildProcessCoverageReporter childProcessCoverageReporter,
      SignalClient.Factory signalClientFactory,
      ExecutionSettings executionSettings) {
    this.parentProcessSessionId = parentProcessSessionId;
    this.parentProcessModuleId = parentProcessModuleId;
    this.config = config;
    this.metricCollector = metricCollector;
    this.testDecorator = testDecorator;
    this.sourcePathResolver = sourcePathResolver;
    this.codeowners = codeowners;
    this.methodLinesResolver = methodLinesResolver;
    this.coverageStoreFactory = coverageStoreFactory;
    this.childProcessCoverageReporter = childProcessCoverageReporter;
    this.signalClientFactory = signalClientFactory;
    this.executionSettings = executionSettings;
  }

  @Override
  public void end(Long startTime) {
    // flushing written traces synchronously:
    // as soon as all tests have been executed,
    // the process can be killed by the build system
    AgentTracer.get().flush();
  }

  @Override
  public TestFrameworkModule testModuleStart(String moduleName, @Nullable Long startTime) {
    return new ProxyTestModule(
        parentProcessSessionId,
        parentProcessModuleId,
        moduleName,
        executionSettings,
        config,
        metricCollector,
        testDecorator,
        sourcePathResolver,
        codeowners,
        methodLinesResolver,
        coverageStoreFactory,
        childProcessCoverageReporter,
        signalClientFactory);
  }
}
