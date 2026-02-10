package datadog.trace.civisibility.events;

import datadog.json.JsonWriter;
import datadog.trace.api.DisableTestTrace;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.DDTest;
import datadog.trace.api.civisibility.DDTestSuite;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.execution.TestExecutionHistory;
import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.api.civisibility.execution.TestStatus;
import datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.tag.EventType;
import datadog.trace.api.civisibility.telemetry.tag.RetryReason;
import datadog.trace.api.civisibility.telemetry.tag.SkipReason;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.domain.TestFrameworkModule;
import datadog.trace.civisibility.domain.TestFrameworkSession;
import datadog.trace.civisibility.domain.TestImpl;
import datadog.trace.civisibility.domain.TestSuiteImpl;
import java.util.Collection;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestEventsHandlerImpl<SuiteKey, TestKey>
    implements TestEventsHandler<SuiteKey, TestKey> {

  private static final Logger log = LoggerFactory.getLogger(TestEventsHandlerImpl.class);

  private final CiVisibilityMetricCollector metricCollector;
  private final TestFrameworkSession testSession;
  private final TestFrameworkModule testModule;
  private final ContextStore<SuiteKey, TestSuiteImpl> inProgressTestSuites;
  private final ContextStore<TestKey, TestImpl> inProgressTests;

  public TestEventsHandlerImpl(
      CiVisibilityMetricCollector metricCollector,
      TestFrameworkSession testSession,
      TestFrameworkModule testModule,
      ContextStore<SuiteKey, DDTestSuite> suiteStore,
      ContextStore<TestKey, DDTest> testStore) {
    this.metricCollector = metricCollector;
    this.testSession = testSession;
    this.testModule = testModule;
    this.inProgressTestSuites = (ContextStore) suiteStore;
    this.inProgressTests = (ContextStore) testStore;
  }

  private static boolean skipTrace(final Class<?> testClass) {
    return testClass != null && testClass.getAnnotation(DisableTestTrace.class) != null;
  }

  @Override
  public void onTestSuiteStart(
      final SuiteKey descriptor,
      final String testSuiteName,
      final @Nullable String testFramework,
      final @Nullable String testFrameworkVersion,
      final @Nullable Class<?> testClass,
      final @Nullable Collection<String> categories,
      boolean parallelized,
      TestFrameworkInstrumentation instrumentation,
      @Nullable Long startTime) {
    if (skipTrace(testClass)) {
      return;
    }

    TestSuiteImpl testSuite =
        testModule.testSuiteStart(
            testSuiteName, testClass, startTime, parallelized, instrumentation);

    if (testFramework != null) {
      testSuite.setTag(Tags.TEST_FRAMEWORK, testFramework);
      if (testFrameworkVersion != null) {
        testSuite.setTag(Tags.TEST_FRAMEWORK_VERSION, testFrameworkVersion);
      }
    }
    if (categories != null && !categories.isEmpty()) {
      testSuite.setTag(Tags.TEST_TRAITS, getTestTraits(categories));
    }

    inProgressTestSuites.put(descriptor, testSuite);
  }

  private String getTestTraits(Collection<String> categories) {
    try (JsonWriter writer = new JsonWriter()) {
      writer.beginObject().name("category").beginArray();
      for (String category : categories) {
        writer.value(category);
      }
      writer.endArray().endObject();
      return writer.toString();
    }
  }

  @Override
  public void onTestSuiteFinish(SuiteKey descriptor, @Nullable Long endTime) {
    if (skipTrace(descriptor.getClass())) {
      return;
    }

    TestSuiteImpl testSuite = inProgressTestSuites.remove(descriptor);
    testSuite.end(endTime);
  }

  @Override
  public void onTestSuiteSkip(SuiteKey descriptor, @Nullable String reason) {
    TestSuiteImpl testSuite = inProgressTestSuites.get(descriptor);
    if (testSuite == null) {
      log.debug("Ignoring skip event, could not find test suite {}", descriptor);
      return;
    }
    testSuite.setSkipReason(reason);
  }

  @Override
  public void onTestSuiteFailure(SuiteKey descriptor, @Nullable Throwable throwable) {
    TestSuiteImpl testSuite = inProgressTestSuites.get(descriptor);
    if (testSuite == null) {
      log.debug("Ignoring fail event, could not find test suite {}", descriptor);
      return;
    }
    testSuite.setErrorInfo(throwable);
  }

  @Override
  public void onTestStart(
      final SuiteKey suiteDescriptor,
      final TestKey descriptor,
      final String testName,
      final @Nullable String testFramework,
      final @Nullable String testFrameworkVersion,
      final @Nullable String testParameters,
      final @Nullable Collection<String> categories,
      final @Nonnull TestSourceData testSourceData,
      final @Nullable Long startTime,
      final @Nullable TestExecutionHistory testExecutionHistory) {
    if (skipTrace(testSourceData.getTestClass())) {
      return;
    }

    TestSuiteImpl testSuite = inProgressTestSuites.get(suiteDescriptor);
    if (testSuite == null) {
      throw new IllegalStateException(
          "Could not find test suite with descriptor "
              + suiteDescriptor
              + "; test descriptor: "
              + descriptor);
    }

    TestImpl test =
        testSuite.testStart(testName, testParameters, testSourceData.getTestMethod(), startTime);

    TestIdentifier thisTest = test.getIdentifier();
    if (testModule.isNew(thisTest)) {
      test.setTag(Tags.TEST_IS_NEW, true);
    }

    if (testModule.isModified(testSourceData)) {
      test.setTag(Tags.TEST_IS_MODIFIED, true);
    }

    if (testModule.isQuarantined(thisTest)) {
      test.setTag(Tags.TEST_TEST_MANAGEMENT_IS_QUARANTINED, true);
    }

    if (testModule.isDisabled(thisTest)) {
      test.setTag(Tags.TEST_TEST_MANAGEMENT_IS_TEST_DISABLED, true);
    }

    if (testModule.isAttemptToFix(thisTest)) {
      test.setTag(Tags.TEST_TEST_MANAGEMENT_IS_ATTEMPT_TO_FIX, true);
    }

    if (testExecutionHistory != null) {
      test.getContext().set(TestExecutionHistory.class, testExecutionHistory);
    }

    if (testFramework != null) {
      test.setTag(Tags.TEST_FRAMEWORK, testFramework);
      if (testFrameworkVersion != null) {
        test.setTag(Tags.TEST_FRAMEWORK_VERSION, testFrameworkVersion);
      }
    }
    if (testParameters != null) {
      test.setTag(Tags.TEST_PARAMETERS, testParameters);
    }
    if (testSourceData.getTestMethodName() != null && testSourceData.getTestMethod() != null) {
      test.setTag(
          Tags.TEST_SOURCE_METHOD,
          testSourceData.getTestMethodName()
              + Type.getMethodDescriptor(testSourceData.getTestMethod()));
    }
    if (categories != null && !categories.isEmpty()) {
      test.setTag(Tags.TEST_TRAITS, getTestTraits(categories));

      for (String category : categories) {
        if (category.endsWith(CIConstants.Tags.ITR_UNSKIPPABLE_TAG)) {
          test.setTag(Tags.TEST_ITR_UNSKIPPABLE, true);
          metricCollector.add(CiVisibilityCountMetric.ITR_UNSKIPPABLE, 1, EventType.TEST);

          if (testModule.skipReason(thisTest) == SkipReason.ITR) {
            test.setTag(Tags.TEST_ITR_FORCED_RUN, true);
            metricCollector.add(CiVisibilityCountMetric.ITR_FORCED_RUN, 1, EventType.TEST);
          }
          break;
        }
      }
    }

    inProgressTests.put(descriptor, test);
  }

  @Override
  public void onTestSkip(TestKey descriptor, @Nullable String reason) {
    TestImpl test = inProgressTests.get(descriptor);
    if (test == null) {
      log.debug("Ignoring skip event, could not find test {}}", descriptor);
      return;
    }
    test.setSkipReason(reason);
  }

  @Override
  public void onTestFailure(TestKey descriptor, @Nullable Throwable throwable) {
    TestImpl test = inProgressTests.get(descriptor);
    if (test == null) {
      log.debug("Ignoring fail event, could not find test {}", descriptor);
      return;
    }
    test.setErrorInfo(throwable);
  }

  @Override
  public void onTestFinish(
      TestKey descriptor,
      @Nullable Long endTime,
      @Nullable TestExecutionHistory testExecutionHistory) {
    TestImpl test = inProgressTests.remove(descriptor);
    if (test == null) {
      log.debug("Ignoring finish event, could not find test {}", descriptor);
      return;
    }

    TestStatus testStatus = test.getStatus() != null ? test.getStatus() : TestStatus.skip;

    if (testExecutionHistory != null) {
      TestExecutionHistory.ExecutionOutcome outcome =
          testExecutionHistory.registerExecution(testStatus, test.getDuration(endTime));

      RetryReason retryReason = outcome.retryReason();
      if (retryReason != null) {
        test.setTag(Tags.TEST_IS_RETRY, true);
        test.setTag(Tags.TEST_RETRY_REASON, retryReason);
      }

      if (outcome.failureSuppressed()) {
        test.setTag(Tags.TEST_FAILURE_SUPPRESSED, true);
      }

      if (outcome.lastExecution()) {
        test.setTag(Tags.TEST_FINAL_STATUS, outcome.finalStatus());

        if (outcome.failedAllRetries()) {
          test.setTag(Tags.TEST_HAS_FAILED_ALL_RETRIES, true);
        }

        if (testModule.isAttemptToFix(test.getIdentifier())) {
          test.setTag(
              Tags.TEST_TEST_MANAGEMENT_ATTEMPT_TO_FIX_PASSED, outcome.succeededAllRetries());
        }
      }
    } else {
      test.setTag(Tags.TEST_FINAL_STATUS, testStatus);
    }

    test.end(endTime);
  }

  @Override
  public void onTestIgnore(
      final SuiteKey suiteDescriptor,
      final TestKey testDescriptor,
      final String testName,
      final @Nullable String testFramework,
      final @Nullable String testFrameworkVersion,
      final @Nullable String testParameters,
      final @Nullable Collection<String> categories,
      @Nonnull TestSourceData testSourceData,
      final @Nullable String reason,
      @Nullable TestExecutionHistory testExecutionHistory) {
    onTestStart(
        suiteDescriptor,
        testDescriptor,
        testName,
        testFramework,
        testFrameworkVersion,
        testParameters,
        categories,
        testSourceData,
        null,
        testExecutionHistory);
    onTestSkip(testDescriptor, reason);
    onTestFinish(testDescriptor, null, testExecutionHistory);
  }

  @Override
  @Nonnull
  public TestExecutionPolicy executionPolicy(
      TestIdentifier test, TestSourceData testSource, Collection<String> testTags) {
    return testModule.executionPolicy(test, testSource, testTags);
  }

  @Override
  public int executionPriority(@Nullable TestIdentifier test, @Nonnull TestSourceData testSource) {
    return testModule.executionPriority(test, testSource);
  }

  @Nullable
  @Override
  public SkipReason skipReason(TestIdentifier test) {
    return testModule.skipReason(test);
  }

  @Override
  public void close() {
    testModule.end(null);
    testSession.end(null);
  }
}
