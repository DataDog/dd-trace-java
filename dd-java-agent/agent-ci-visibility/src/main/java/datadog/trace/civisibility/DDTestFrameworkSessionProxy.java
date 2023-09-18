package datadog.trace.civisibility;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.coverage.CoverageDataSupplier;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.coverage.CoverageProbeStoreFactory;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.source.MethodLinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import java.net.InetSocketAddress;
import javax.annotation.Nullable;

/**
 * Test session implementation that is used by test framework instrumentations in those cases when
 * the build system IS instrumented: since build system instrumentation manages the session span,
 * this class does not do it
 */
public class DDTestFrameworkSessionProxy implements DDTestFrameworkSession {

  private final long parentProcessSessionId;
  private final long parentProcessModuleId;
  private final Config config;
  private final TestDecorator testDecorator;
  private final SourcePathResolver sourcePathResolver;
  private final Codeowners codeowners;
  private final MethodLinesResolver methodLinesResolver;
  private final CoverageProbeStoreFactory coverageProbeStoreFactory;
  private final CoverageDataSupplier coverageDataSupplier;
  @Nullable private final InetSocketAddress signalServerAddress;

  public DDTestFrameworkSessionProxy(
      long parentProcessSessionId,
      long parentProcessModuleId,
      Config config,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver,
      Codeowners codeowners,
      MethodLinesResolver methodLinesResolver,
      CoverageProbeStoreFactory coverageProbeStoreFactory,
      CoverageDataSupplier coverageDataSupplier,
      @Nullable InetSocketAddress signalServerAddress) {
    this.parentProcessSessionId = parentProcessSessionId;
    this.parentProcessModuleId = parentProcessModuleId;
    this.config = config;
    this.testDecorator = testDecorator;
    this.sourcePathResolver = sourcePathResolver;
    this.codeowners = codeowners;
    this.methodLinesResolver = methodLinesResolver;
    this.coverageProbeStoreFactory = coverageProbeStoreFactory;
    this.coverageDataSupplier = coverageDataSupplier;
    this.signalServerAddress = signalServerAddress;
  }

  @Override
  public void end(Long startTime) {
    // no op
  }

  @Override
  public DDTestFrameworkModule testModuleStart(String moduleName, @Nullable Long startTime) {
    return new DDTestFrameworkModuleProxy(
        parentProcessSessionId,
        parentProcessModuleId,
        moduleName,
        config,
        testDecorator,
        sourcePathResolver,
        codeowners,
        methodLinesResolver,
        coverageProbeStoreFactory,
        coverageDataSupplier,
        signalServerAddress);
  }
}
