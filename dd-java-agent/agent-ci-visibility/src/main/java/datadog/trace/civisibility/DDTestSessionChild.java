package datadog.trace.civisibility;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import datadog.trace.api.civisibility.coverage.CoverageDataSupplier;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.config.JvmInfo;
import datadog.trace.civisibility.coverage.CoverageProbeStoreFactory;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.source.MethodLinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import java.io.File;
import java.net.InetSocketAddress;
import java.util.Collection;
import javax.annotation.Nullable;

public class DDTestSessionChild extends DDTestSessionImpl {

  private final Long parentProcessSessionId;
  private final Long parentProcessModuleId;
  private final Config config;
  private final TestDecorator testDecorator;
  private final SourcePathResolver sourcePathResolver;
  private final Codeowners codeowners;
  private final MethodLinesResolver methodLinesResolver;
  private final CoverageProbeStoreFactory coverageProbeStoreFactory;
  private final CoverageDataSupplier coverageDataSupplier;
  @Nullable private final InetSocketAddress signalServerAddress;

  public DDTestSessionChild(
      Long parentProcessSessionId,
      Long parentProcessModuleId,
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
  public void setTag(String key, Object value) {
    throw new UnsupportedOperationException("Setting tags is not supported: " + key + ", " + value);
  }

  @Override
  public void setErrorInfo(Throwable error) {
    throw new UnsupportedOperationException("Setting error info is not supported: " + error);
  }

  @Override
  public void setSkipReason(String skipReason) {
    throw new UnsupportedOperationException("Setting skip reason is not supported: " + skipReason);
  }

  @Override
  public void end(@Nullable Long endTime) {
    // no op
  }

  @Override
  public DDTestModuleImpl testModuleStart(
      String moduleName, @Nullable Long startTime, Collection<File> outputClassesDirs) {
    return new DDTestModuleChild(
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

  @Override
  public ModuleExecutionSettings getModuleExecutionSettings(JvmInfo jvmInfo) {
    throw new UnsupportedOperationException(
        "Getting module execution settings is not supported: " + jvmInfo);
  }
}
