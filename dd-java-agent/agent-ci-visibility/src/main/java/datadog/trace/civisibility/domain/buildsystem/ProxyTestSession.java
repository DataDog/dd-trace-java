package datadog.trace.civisibility.domain.buildsystem;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import datadog.trace.api.civisibility.coverage.CoverageDataSupplier;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.coverage.CoverageProbeStoreFactory;
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
  private final CoverageProbeStoreFactory coverageProbeStoreFactory;
  private final CoverageDataSupplier coverageDataSupplier;
  private final SignalClient.Factory signalClientFactory;
  private final ModuleExecutionSettings moduleExecutionSettings;

  public ProxyTestSession(
      long parentProcessSessionId,
      long parentProcessModuleId,
      Config config,
      CiVisibilityMetricCollector metricCollector,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver,
      Codeowners codeowners,
      MethodLinesResolver methodLinesResolver,
      CoverageProbeStoreFactory coverageProbeStoreFactory,
      CoverageDataSupplier coverageDataSupplier,
      SignalClient.Factory signalClientFactory,
      ModuleExecutionSettings moduleExecutionSettings) {
    this.parentProcessSessionId = parentProcessSessionId;
    this.parentProcessModuleId = parentProcessModuleId;
    this.config = config;
    this.metricCollector = metricCollector;
    this.testDecorator = testDecorator;
    this.sourcePathResolver = sourcePathResolver;
    this.codeowners = codeowners;
    this.methodLinesResolver = methodLinesResolver;
    this.coverageProbeStoreFactory = coverageProbeStoreFactory;
    this.coverageDataSupplier = coverageDataSupplier;
    this.signalClientFactory = signalClientFactory;
    this.moduleExecutionSettings = moduleExecutionSettings;
  }

  @Override
  public void end(Long startTime) {
    // no op
  }

  @Override
  public TestFrameworkModule testModuleStart(String moduleName, @Nullable Long startTime) {
    return new ProxyTestModule(
        parentProcessSessionId,
        parentProcessModuleId,
        moduleName,
        moduleExecutionSettings,
        config,
        metricCollector,
        testDecorator,
        sourcePathResolver,
        codeowners,
        methodLinesResolver,
        coverageProbeStoreFactory,
        coverageDataSupplier,
        signalClientFactory);
  }
}
