package datadog.trace.civisibility;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.coverage.CoverageDataSupplier;
import datadog.trace.api.civisibility.retry.TestRetryPolicy;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.config.JvmInfo;
import datadog.trace.civisibility.coverage.CoverageProbeStoreFactory;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.ipc.ModuleExecutionResult;
import datadog.trace.civisibility.ipc.SignalClient;
import datadog.trace.civisibility.ipc.TestDataRequest;
import datadog.trace.civisibility.ipc.TestDataResponse;
import datadog.trace.civisibility.ipc.TestDataType;
import datadog.trace.civisibility.ipc.TestFramework;
import datadog.trace.civisibility.retry.NeverRetry;
import datadog.trace.civisibility.retry.RetryIfFailed;
import datadog.trace.civisibility.source.MethodLinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test module implementation that is used by test framework instrumentations in those cases when
 * the build system IS instrumented: since build system instrumentation manages module spans, this
 * class does not do it. Instead, it accumulates module execution data and forwards it to the parent
 * process (build system) using the signal server
 */
public class DDTestFrameworkModuleProxy implements DDTestFrameworkModule {
  private static final Logger log = LoggerFactory.getLogger(DDTestFrameworkModuleProxy.class);

  private final long parentProcessSessionId;
  private final long parentProcessModuleId;
  private final String moduleName;
  private final InetSocketAddress signalServerAddress;
  private final CoverageDataSupplier coverageDataSupplier;
  private final Config config;
  private final TestDecorator testDecorator;
  private final SourcePathResolver sourcePathResolver;
  private final Codeowners codeowners;
  private final MethodLinesResolver methodLinesResolver;
  private final CoverageProbeStoreFactory coverageProbeStoreFactory;
  private final LongAdder testsSkipped = new LongAdder();
  private final Collection<TestIdentifier> skippableTests;
  private final Collection<TestIdentifier> flakyTests;
  private final Collection<TestFramework> testFrameworks = ConcurrentHashMap.newKeySet();

  public DDTestFrameworkModuleProxy(
      long parentProcessSessionId,
      long parentProcessModuleId,
      String moduleName,
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
    this.moduleName = moduleName;
    this.signalServerAddress = signalServerAddress;
    this.coverageDataSupplier = coverageDataSupplier;
    this.config = config;
    this.testDecorator = testDecorator;
    this.sourcePathResolver = sourcePathResolver;
    this.codeowners = codeowners;
    this.methodLinesResolver = methodLinesResolver;
    this.coverageProbeStoreFactory = coverageProbeStoreFactory;
    this.skippableTests = fetchSkippableTests(moduleName, signalServerAddress);
    this.flakyTests = fetchFlakyTests(moduleName, signalServerAddress);
  }

  private Collection<TestIdentifier> fetchSkippableTests(
      String moduleName, InetSocketAddress signalServerAddress) {
    return config.isCiVisibilityItrEnabled()
        ? fetchTestData(TestDataType.SKIPPABLE, moduleName, signalServerAddress)
        : Collections.emptyList();
  }

  private Collection<TestIdentifier> fetchFlakyTests(
      String moduleName, InetSocketAddress signalServerAddress) {
    return config.isCiVisibilityFlakyRetryEnabled()
        ? fetchTestData(TestDataType.FLAKY, moduleName, signalServerAddress)
        : Collections.emptyList();
  }

  private Collection<TestIdentifier> fetchTestData(
      TestDataType testDataType, String moduleName, InetSocketAddress signalServerAddress) {
    TestDataRequest request = new TestDataRequest(testDataType, moduleName, JvmInfo.CURRENT_JVM);
    try (SignalClient signalClient = new SignalClient(signalServerAddress)) {
      TestDataResponse response = (TestDataResponse) signalClient.send(request);
      Collection<TestIdentifier> testData = response.getTests();
      log.debug("Received {} {} tests", testData.size(), testDataType);
      return new HashSet<>(testData);
    } catch (Exception e) {
      log.error("Error while requesting {} test data", testDataType, e);
      return Collections.emptySet();
    }
  }

  @Override
  public boolean isSkippable(TestIdentifier test) {
    return test != null && skippableTests.contains(test);
  }

  @Override
  public boolean skip(TestIdentifier test) {
    if (isSkippable(test)) {
      testsSkipped.increment();
      return true;
    } else {
      return false;
    }
  }

  @Override
  public TestRetryPolicy retryPolicy(TestIdentifier test) {
    return test != null && flakyTests.contains(test)
        ? new RetryIfFailed(config.getCiVisibilityFlakyRetryCount())
        : NeverRetry.INSTANCE;
  }

  @Override
  public void end(@Nullable Long endTime) {
    // we have no span locally,
    // send execution result to parent process that manages the span
    sendModuleExecutionResult();
  }

  private void sendModuleExecutionResult() {
    boolean coverageEnabled = config.isCiVisibilityCodeCoverageEnabled();
    boolean itrEnabled = config.isCiVisibilityItrEnabled();
    long testsSkippedTotal = testsSkipped.sum();
    byte[] coverageData = coverageDataSupplier.get();

    ModuleExecutionResult moduleExecutionResult =
        new ModuleExecutionResult(
            parentProcessSessionId,
            parentProcessModuleId,
            coverageEnabled,
            itrEnabled,
            testsSkippedTotal,
            new TreeSet<>(testFrameworks),
            coverageData);

    try (SignalClient signalClient = new SignalClient(signalServerAddress)) {
      signalClient.send(moduleExecutionResult);
    } catch (Exception e) {
      log.error("Error while reporting module execution result", e);
    }
  }

  @Override
  public DDTestSuiteImpl testSuiteStart(
      String testSuiteName,
      @Nullable Class<?> testClass,
      @Nullable Long startTime,
      boolean parallelized) {
    return new DDTestSuiteImpl(
        null,
        parentProcessSessionId,
        parentProcessModuleId,
        moduleName,
        testSuiteName,
        testClass,
        startTime,
        parallelized,
        config,
        testDecorator,
        sourcePathResolver,
        codeowners,
        methodLinesResolver,
        coverageProbeStoreFactory,
        this::propagateTestFrameworkData);
  }

  private void propagateTestFrameworkData(AgentSpan childSpan) {
    testFrameworks.add(
        new TestFramework(
            (String) childSpan.getTag(Tags.TEST_FRAMEWORK),
            (String) childSpan.getTag(Tags.TEST_FRAMEWORK_VERSION)));
  }
}
