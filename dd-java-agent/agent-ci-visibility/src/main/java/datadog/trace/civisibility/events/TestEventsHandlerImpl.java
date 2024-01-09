package datadog.trace.civisibility.events;

import static datadog.trace.util.Strings.toJson;

import datadog.trace.api.DisableTestTrace;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.retry.TestRetryPolicy;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.DDTestFrameworkModule;
import datadog.trace.civisibility.DDTestFrameworkSession;
import datadog.trace.civisibility.DDTestImpl;
import datadog.trace.civisibility.DDTestSuiteImpl;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestEventsHandlerImpl implements TestEventsHandler {

  private static final Logger log = LoggerFactory.getLogger(TestEventsHandlerImpl.class);

  private final DDTestFrameworkSession testSession;
  private final DDTestFrameworkModule testModule;

  private final ConcurrentMap<TestSuiteDescriptor, Integer> testSuiteNestedCallCounters =
      new ConcurrentHashMap<>();

  private final ConcurrentMap<TestSuiteDescriptor, DDTestSuiteImpl> inProgressTestSuites =
      new ConcurrentHashMap<>();

  private final ConcurrentMap<TestDescriptor, DDTestImpl> inProgressTests =
      new ConcurrentHashMap<>();

  public TestEventsHandlerImpl(
      DDTestFrameworkSession testSession, DDTestFrameworkModule testModule) {
    this.testSession = testSession;
    this.testModule = testModule;
  }

  @Override
  public void onTestSuiteStart(
      final String testSuiteName,
      final @Nullable String testFramework,
      final @Nullable String testFrameworkVersion,
      final @Nullable Class<?> testClass,
      final @Nullable Collection<String> categories,
      boolean parallelized) {
    if (skipTrace(testClass)) {
      return;
    }

    TestSuiteDescriptor descriptor = new TestSuiteDescriptor(testSuiteName, testClass);
    if (!tryTestSuiteStart(descriptor)) {
      return;
    }

    DDTestSuiteImpl testSuite =
        testModule.testSuiteStart(testSuiteName, testClass, null, parallelized);

    if (testFramework != null) {
      testSuite.setTag(Tags.TEST_FRAMEWORK, testFramework);
    }
    if (testFrameworkVersion != null) {
      testSuite.setTag(Tags.TEST_FRAMEWORK_VERSION, testFrameworkVersion);
    }
    if (categories != null && !categories.isEmpty()) {
      testSuite.setTag(
          Tags.TEST_TRAITS, toJson(Collections.singletonMap("category", toJson(categories)), true));
    }

    inProgressTestSuites.put(descriptor, testSuite);
  }

  @Override
  public void onTestSuiteFinish(final String testSuiteName, final @Nullable Class<?> testClass) {
    if (skipTrace(testClass)) {
      return;
    }

    TestSuiteDescriptor descriptor = new TestSuiteDescriptor(testSuiteName, testClass);
    if (!tryTestSuiteFinish(descriptor)) {
      return;
    }

    DDTestSuiteImpl testSuite = inProgressTestSuites.remove(descriptor);
    testSuite.end(null);
  }

  private boolean tryTestSuiteStart(TestSuiteDescriptor descriptor) {
    return testSuiteNestedCallCounters.merge(descriptor, 1, Integer::sum) == 1;
  }

  private boolean tryTestSuiteFinish(TestSuiteDescriptor descriptor) {
    return testSuiteNestedCallCounters.merge(descriptor, -1, (a, b) -> a + b > 0 ? a + b : null)
        == null;
  }

  @Override
  public void onTestSuiteSkip(String testSuiteName, Class<?> testClass, @Nullable String reason) {
    TestSuiteDescriptor descriptor = new TestSuiteDescriptor(testSuiteName, testClass);
    DDTestSuiteImpl testSuite = inProgressTestSuites.get(descriptor);
    if (testSuite == null) {
      log.debug(
          "Ignoring skip event, could not find test suite with name {} and class {}",
          testSuiteName,
          testClass);
      return;
    }
    testSuite.setSkipReason(reason);
  }

  @Override
  public void onTestSuiteFailure(
      String testSuiteName, Class<?> testClass, @Nullable Throwable throwable) {
    TestSuiteDescriptor descriptor = new TestSuiteDescriptor(testSuiteName, testClass);
    DDTestSuiteImpl testSuite = inProgressTestSuites.get(descriptor);
    if (testSuite == null) {
      log.debug(
          "Ignoring fail event, could not find test suite with name {} and class {}",
          testSuiteName,
          testClass);
      return;
    }
    testSuite.setErrorInfo(throwable);
  }

  @Override
  public void onTestStart(
      final String testSuiteName,
      final String testName,
      final @Nullable Object testQualifier,
      final @Nullable String testFramework,
      final @Nullable String testFrameworkVersion,
      final @Nullable String testParameters,
      final @Nullable Collection<String> categories,
      final @Nullable Class<?> testClass,
      final @Nullable String testMethodName,
      final @Nullable Method testMethod) {
    if (skipTrace(testClass)) {
      return;
    }

    TestSuiteDescriptor suiteDescriptor = new TestSuiteDescriptor(testSuiteName, testClass);
    DDTestSuiteImpl testSuite = inProgressTestSuites.get(suiteDescriptor);
    DDTestImpl test = testSuite.testStart(testName, testMethod, null);

    if (testFramework != null) {
      test.setTag(Tags.TEST_FRAMEWORK, testFramework);
    }
    if (testFrameworkVersion != null) {
      test.setTag(Tags.TEST_FRAMEWORK_VERSION, testFrameworkVersion);
    }
    if (testParameters != null) {
      test.setTag(Tags.TEST_PARAMETERS, testParameters);
    }
    if (testMethodName != null && testMethod != null) {
      test.setTag(Tags.TEST_SOURCE_METHOD, testMethodName + Type.getMethodDescriptor(testMethod));
    }
    if (categories != null && !categories.isEmpty()) {
      String json = toJson(Collections.singletonMap("category", toJson(categories)), true);
      test.setTag(Tags.TEST_TRAITS, json);

      for (String category : categories) {
        if (category.endsWith(InstrumentationBridge.ITR_UNSKIPPABLE_TAG)) {
          test.setTag(Tags.TEST_ITR_UNSKIPPABLE, true);

          TestIdentifier thisTest =
              new TestIdentifier(testSuiteName, testName, testParameters, null);
          if (testModule.isSkippable(thisTest)) {
            test.setTag(Tags.TEST_ITR_FORCED_RUN, true);
          }
          break;
        }
      }
    }

    TestDescriptor descriptor =
        new TestDescriptor(testSuiteName, testClass, testName, testParameters, testQualifier);
    inProgressTests.put(descriptor, test);
  }

  @Override
  public void onTestSkip(
      String testSuiteName,
      Class<?> testClass,
      String testName,
      @Nullable Object testQualifier,
      @Nullable String testParameters,
      @Nullable String reason) {
    TestDescriptor descriptor =
        new TestDescriptor(testSuiteName, testClass, testName, testParameters, testQualifier);
    DDTestImpl test = inProgressTests.get(descriptor);
    if (test == null) {
      log.debug(
          "Ignoring skip event, could not find test with name {}, suite name{} and class {}",
          testName,
          testSuiteName,
          testClass);
      return;
    }
    test.setSkipReason(reason);
  }

  @Override
  public void onTestFailure(
      String testSuiteName,
      Class<?> testClass,
      String testName,
      @Nullable Object testQualifier,
      @Nullable String testParameters,
      @Nullable Throwable throwable) {
    TestDescriptor descriptor =
        new TestDescriptor(testSuiteName, testClass, testName, testParameters, testQualifier);
    DDTestImpl test = inProgressTests.get(descriptor);
    if (test == null) {
      log.debug(
          "Ignoring fail event, could not find test with name {}, suite name{} and class {}",
          testName,
          testSuiteName,
          testClass);
      return;
    }
    test.setErrorInfo(throwable);
  }

  @Override
  public void onTestFinish(
      final String testSuiteName,
      final Class<?> testClass,
      final String testName,
      final @Nullable Object testQualifier,
      final @Nullable String testParameters) {
    TestDescriptor descriptor =
        new TestDescriptor(testSuiteName, testClass, testName, testParameters, testQualifier);
    DDTestImpl test = inProgressTests.remove(descriptor);
    if (test == null) {
      log.debug(
          "Ignoring finish event, could not find test with name {}, suite name{} and class {}",
          testName,
          testSuiteName,
          testClass);
      return;
    }
    test.end(null);
  }

  @Override
  public void onTestIgnore(
      final String testSuiteName,
      final String testName,
      final @Nullable Object testQualifier,
      final @Nullable String testFramework,
      final @Nullable String testFrameworkVersion,
      final @Nullable String testParameters,
      final @Nullable Collection<String> categories,
      final @Nullable Class<?> testClass,
      final @Nullable String testMethodName,
      final @Nullable Method testMethod,
      final @Nullable String reason) {
    onTestStart(
        testSuiteName,
        testName,
        testQualifier,
        testFramework,
        testFrameworkVersion,
        testParameters,
        categories,
        testClass,
        testMethodName,
        testMethod);
    onTestSkip(testSuiteName, testClass, testName, testQualifier, testParameters, reason);
    onTestFinish(testSuiteName, testClass, testName, testQualifier, testParameters);
  }

  private static boolean skipTrace(final Class<?> testClass) {
    return testClass != null && testClass.getAnnotation(DisableTestTrace.class) != null;
  }

  @Override
  public boolean skip(TestIdentifier test) {
    return testModule.skip(test);
  }

  @Override
  @Nonnull
  public TestRetryPolicy retryPolicy(TestIdentifier test) {
    return testModule.retryPolicy(test);
  }

  @Override
  public void close() {
    testModule.end(null);
    testSession.end(null);
  }
}
