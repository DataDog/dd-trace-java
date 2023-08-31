package datadog.trace.civisibility;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.config.SkippableTest;
import datadog.trace.api.civisibility.coverage.CoverageDataSupplier;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.config.JvmInfo;
import datadog.trace.civisibility.context.ParentProcessTestContext;
import datadog.trace.civisibility.coverage.CoverageProbeStoreFactory;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.ipc.ModuleExecutionResult;
import datadog.trace.civisibility.ipc.SignalClient;
import datadog.trace.civisibility.ipc.SkippableTestsRequest;
import datadog.trace.civisibility.ipc.SkippableTestsResponse;
import datadog.trace.civisibility.source.MethodLinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Collections;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Representation of a test module in a child process (JVM that is forked by build system to run the
 * tests)
 */
public class DDTestModuleChild extends DDTestModuleImpl {

  private static final Logger log = LoggerFactory.getLogger(DDTestModuleChild.class);

  private final ParentProcessTestContext context;
  private final CoverageDataSupplier coverageDataSupplier;

  public DDTestModuleChild(
      Long parentProcessSessionId,
      Long parentProcessModuleId,
      String moduleName,
      Config config,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver,
      Codeowners codeowners,
      MethodLinesResolver methodLinesResolver,
      CoverageProbeStoreFactory coverageProbeStoreFactory,
      CoverageDataSupplier coverageDataSupplier,
      @Nullable InetSocketAddress signalServerAddress) {
    super(
        moduleName,
        config,
        testDecorator,
        sourcePathResolver,
        codeowners,
        methodLinesResolver,
        coverageProbeStoreFactory,
        signalServerAddress);
    context = new ParentProcessTestContext(parentProcessSessionId, parentProcessModuleId);
    this.coverageDataSupplier = coverageDataSupplier;
  }

  @Override
  protected ParentProcessTestContext getContext() {
    return context;
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
    // we have no span locally,
    // send execution result to parent process that has the span
    sendModuleExecutionResult();
  }

  private void sendModuleExecutionResult() {
    long moduleId = context.getId();
    long sessionId = context.getParentId();
    boolean coverageEnabled = config.isCiVisibilityCodeCoverageEnabled();
    boolean itrEnabled = config.isCiVisibilityItrEnabled();
    long testsSkippedTotal = testsSkipped.sum();
    String testFramework = String.valueOf(context.getChildTag(Tags.TEST_FRAMEWORK));
    String testFrameworkVersion = String.valueOf(context.getChildTag(Tags.TEST_FRAMEWORK_VERSION));
    byte[] coverageData = coverageDataSupplier.get();

    ModuleExecutionResult moduleExecutionResult =
        new ModuleExecutionResult(
            sessionId,
            moduleId,
            coverageEnabled,
            itrEnabled,
            testsSkippedTotal,
            testFramework,
            testFrameworkVersion,
            coverageData);

    try (SignalClient signalClient = new SignalClient(signalServerAddress)) {
      signalClient.send(moduleExecutionResult);
    } catch (Exception e) {
      log.error("Error while reporting module execution result", e);
    }
  }

  @Override
  protected Collection<SkippableTest> fetchSkippableTests() {
    SkippableTestsRequest request = new SkippableTestsRequest(moduleName, JvmInfo.CURRENT_JVM);
    try (SignalClient signalClient = new SignalClient(signalServerAddress)) {
      SkippableTestsResponse response = (SkippableTestsResponse) signalClient.send(request);
      Collection<SkippableTest> tests = response.getTests();
      log.debug("Received {} skippable tests", tests.size());
      return tests;
    } catch (Exception e) {
      log.error("Error while requesting skippable tests", e);
      return Collections.emptySet();
    }
  }
}
