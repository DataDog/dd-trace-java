package datadog.trace.civisibility.domain.buildsystem;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.config.EarlyFlakeDetectionSettings;
import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.coverage.CoverageDataSupplier;
import datadog.trace.api.civisibility.coverage.CoverageStore;
import datadog.trace.api.civisibility.retry.TestRetryPolicy;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.InstrumentationType;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.coverage.SkippableAwareCoverageStoreFactory;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.domain.TestFrameworkModule;
import datadog.trace.civisibility.domain.TestSuiteImpl;
import datadog.trace.civisibility.ipc.ModuleExecutionResult;
import datadog.trace.civisibility.ipc.SignalClient;
import datadog.trace.civisibility.ipc.TestFramework;
import datadog.trace.civisibility.retry.NeverRetry;
import datadog.trace.civisibility.retry.RetryIfFailed;
import datadog.trace.civisibility.retry.RetryNTimes;
import datadog.trace.civisibility.source.MethodLinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import java.util.Collection;
import java.util.HashSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test module implementation that is used by test framework instrumentations in those cases when
 * the build system IS instrumented: since build system instrumentation manages module spans, this
 * class does not do it. Instead, it accumulates module execution data and forwards it to the parent
 * process (build system) using the signal server
 */
public class ProxyTestModule implements TestFrameworkModule {
  private static final Logger log = LoggerFactory.getLogger(ProxyTestModule.class);

  private final long parentProcessSessionId;
  private final long parentProcessModuleId;
  private final String moduleName;
  private final String itrCorrelationId;
  private final SignalClient.Factory signalClientFactory;
  private final CoverageDataSupplier coverageDataSupplier;
  private final Config config;
  private final CiVisibilityMetricCollector metricCollector;
  private final TestDecorator testDecorator;
  private final SourcePathResolver sourcePathResolver;
  private final Codeowners codeowners;
  private final MethodLinesResolver methodLinesResolver;
  private final CoverageStore.Factory coverageStoreFactory;
  private final LongAdder testsSkipped = new LongAdder();
  private final Collection<TestIdentifier> skippableTests;
  private final boolean testSkippingEnabled;
  private final boolean flakyTestRetriesEnabled;
  @Nullable private final Collection<TestIdentifier> flakyTests;
  private final Collection<TestIdentifier> knownTests;
  private final EarlyFlakeDetectionSettings earlyFlakeDetectionSettings;
  private final AtomicInteger earlyFlakeDetectionsUsed = new AtomicInteger(0);
  private final Collection<TestFramework> testFrameworks = ConcurrentHashMap.newKeySet();

  public ProxyTestModule(
      long parentProcessSessionId,
      long parentProcessModuleId,
      String moduleName,
      ModuleExecutionSettings executionSettings,
      Config config,
      CiVisibilityMetricCollector metricCollector,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver,
      Codeowners codeowners,
      MethodLinesResolver methodLinesResolver,
      CoverageStore.Factory coverageStoreFactory,
      CoverageDataSupplier coverageDataSupplier,
      SignalClient.Factory signalClientFactory) {
    this.parentProcessSessionId = parentProcessSessionId;
    this.parentProcessModuleId = parentProcessModuleId;
    this.moduleName = moduleName;
    this.signalClientFactory = signalClientFactory;
    this.coverageDataSupplier = coverageDataSupplier;
    this.config = config;
    this.metricCollector = metricCollector;
    this.testDecorator = testDecorator;
    this.sourcePathResolver = sourcePathResolver;
    this.codeowners = codeowners;
    this.methodLinesResolver = methodLinesResolver;
    this.itrCorrelationId = executionSettings.getItrCorrelationId();

    this.testSkippingEnabled = executionSettings.isTestSkippingEnabled();
    this.skippableTests = new HashSet<>(executionSettings.getSkippableTests(moduleName));
    this.coverageStoreFactory =
        executionSettings.isItrEnabled()
            ? new SkippableAwareCoverageStoreFactory(skippableTests, coverageStoreFactory)
            : coverageStoreFactory;

    this.flakyTestRetriesEnabled = executionSettings.isFlakyTestRetriesEnabled();
    Collection<TestIdentifier> flakyTests = executionSettings.getFlakyTests(moduleName);
    this.flakyTests = flakyTests != null ? new HashSet<>(flakyTests) : null;

    Collection<TestIdentifier> moduleKnownTests = executionSettings.getKnownTests(moduleName);
    this.knownTests = moduleKnownTests != null ? new HashSet<>(moduleKnownTests) : null;

    this.earlyFlakeDetectionSettings = executionSettings.getEarlyFlakeDetectionSettings();
  }

  @Override
  public boolean isNew(TestIdentifier test) {
    return knownTests != null && !knownTests.contains(test.withoutParameters());
  }

  @Override
  public boolean shouldBeSkipped(TestIdentifier test) {
    return testSkippingEnabled && test != null && skippableTests.contains(test);
  }

  @Override
  public boolean skip(TestIdentifier test) {
    if (shouldBeSkipped(test)) {
      testsSkipped.increment();
      return true;
    } else {
      return false;
    }
  }

  @Override
  @Nonnull
  public TestRetryPolicy retryPolicy(TestIdentifier test) {
    if (test != null) {
      if (earlyFlakeDetectionSettings.isEnabled()
          && !knownTests.contains(test.withoutParameters())
          && !earlyFlakeDetectionLimitReached(earlyFlakeDetectionsUsed.incrementAndGet())) {
        return new RetryNTimes(earlyFlakeDetectionSettings);
      }
      if (flakyTestRetriesEnabled
          && (flakyTests == null || flakyTests.contains(test.withoutParameters()))) {
        return new RetryIfFailed(config.getCiVisibilityFlakyRetryCount());
      }
    }
    return NeverRetry.INSTANCE;
  }

  private boolean earlyFlakeDetectionLimitReached(int earlyFlakeDetectionsUsed) {
    int totalTests = knownTests.size() + earlyFlakeDetectionsUsed;
    int threshold =
        Math.max(
            config.getCiVisibilityEarlyFlakeDetectionLowerLimit(),
            totalTests * earlyFlakeDetectionSettings.getFaultySessionThreshold() / 100);
    return earlyFlakeDetectionsUsed > threshold;
  }

  @Override
  public void end(@Nullable Long endTime) {
    // we have no span locally,
    // send execution result to parent process that manages the span
    sendModuleExecutionResult();
  }

  private void sendModuleExecutionResult() {
    boolean coverageEnabled = config.isCiVisibilityCodeCoverageEnabled();
    boolean testSkippingEnabled = config.isCiVisibilityTestSkippingEnabled();
    boolean earlyFlakeDetectionEnabled = earlyFlakeDetectionSettings.isEnabled();
    boolean earlyFlakeDetectionFaulty =
        earlyFlakeDetectionEnabled
            && earlyFlakeDetectionLimitReached(earlyFlakeDetectionsUsed.get());
    long testsSkippedTotal = testsSkipped.sum();
    byte[] coverageData = coverageDataSupplier.get();

    ModuleExecutionResult moduleExecutionResult =
        new ModuleExecutionResult(
            parentProcessSessionId,
            parentProcessModuleId,
            coverageEnabled,
            testSkippingEnabled,
            earlyFlakeDetectionEnabled,
            earlyFlakeDetectionFaulty,
            testsSkippedTotal,
            new TreeSet<>(testFrameworks),
            coverageData);

    try (SignalClient signalClient = signalClientFactory.create()) {
      signalClient.send(moduleExecutionResult);
    } catch (Exception e) {
      log.error("Error while reporting module execution result", e);
    }
  }

  @Override
  public TestSuiteImpl testSuiteStart(
      String testSuiteName,
      @Nullable Class<?> testClass,
      @Nullable Long startTime,
      boolean parallelized,
      TestFrameworkInstrumentation instrumentation) {
    return new TestSuiteImpl(
        null,
        parentProcessSessionId,
        parentProcessModuleId,
        moduleName,
        testSuiteName,
        itrCorrelationId,
        testClass,
        startTime,
        parallelized,
        InstrumentationType.BUILD,
        instrumentation,
        config,
        metricCollector,
        testDecorator,
        sourcePathResolver,
        codeowners,
        methodLinesResolver,
        coverageStoreFactory,
        this::propagateTestFrameworkData);
  }

  private void propagateTestFrameworkData(AgentSpan childSpan) {
    testFrameworks.add(
        new TestFramework(
            (String) childSpan.getTag(Tags.TEST_FRAMEWORK),
            (String) childSpan.getTag(Tags.TEST_FRAMEWORK_VERSION)));
  }
}
