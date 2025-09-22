package datadog.trace.civisibility.domain.buildsystem;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.config.LibraryCapability;
import datadog.trace.api.civisibility.coverage.CoverageStore;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.coverage.report.child.ChildProcessCoverageReporter;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.domain.TestFrameworkModule;
import datadog.trace.civisibility.domain.TestFrameworkSession;
import datadog.trace.civisibility.ipc.SignalClient;
import datadog.trace.civisibility.source.LinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import datadog.trace.civisibility.test.ExecutionStrategy;
import java.util.Collection;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Test session implementation that is used by test framework instrumentations in those cases when
 * the build system IS instrumented: since build system instrumentation manages the session span,
 * this class does not do it.
 */
public class ProxyTestSession implements TestFrameworkSession {

  private final AgentSpanContext parentProcessModuleContext;
  private final Config config;
  private final CiVisibilityMetricCollector metricCollector;
  private final TestDecorator testDecorator;
  private final SourcePathResolver sourcePathResolver;
  private final Codeowners codeowners;
  private final LinesResolver linesResolver;
  private final CoverageStore.Factory coverageStoreFactory;
  private final ChildProcessCoverageReporter childProcessCoverageReporter;
  private final SignalClient.Factory signalClientFactory;
  private final ExecutionStrategy executionStrategy;
  private final Collection<LibraryCapability> capabilities;

  public ProxyTestSession(
      AgentSpanContext parentProcessModuleContext,
      Config config,
      CiVisibilityMetricCollector metricCollector,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver,
      Codeowners codeowners,
      LinesResolver linesResolver,
      CoverageStore.Factory coverageStoreFactory,
      ChildProcessCoverageReporter childProcessCoverageReporter,
      SignalClient.Factory signalClientFactory,
      ExecutionStrategy executionStrategy,
      @Nonnull Collection<LibraryCapability> capabilities) {
    this.parentProcessModuleContext = parentProcessModuleContext;
    this.config = config;
    this.metricCollector = metricCollector;
    this.testDecorator = testDecorator;
    this.sourcePathResolver = sourcePathResolver;
    this.codeowners = codeowners;
    this.linesResolver = linesResolver;
    this.coverageStoreFactory = coverageStoreFactory;
    this.childProcessCoverageReporter = childProcessCoverageReporter;
    this.signalClientFactory = signalClientFactory;
    this.executionStrategy = executionStrategy;
    this.capabilities = capabilities;
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
        parentProcessModuleContext,
        moduleName,
        executionStrategy,
        config,
        metricCollector,
        testDecorator,
        sourcePathResolver,
        codeowners,
        linesResolver,
        coverageStoreFactory,
        childProcessCoverageReporter,
        signalClientFactory,
        capabilities);
  }
}
