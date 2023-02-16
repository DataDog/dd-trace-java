package datadog.trace.bootstrap.instrumentation.civisibility;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DisableTestTrace;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.decorator.TestDecorator;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;

public class TestEventsHandler {

  public static final String TEST_PASS = "pass";
  public static final String TEST_FAIL = "fail";
  public static final String TEST_SKIP = "skip";

  private volatile TestContext testModuleContext;

  private final ConcurrentMap<TestSuiteDescriptor, Integer> testSuiteNestedCallCounters =
      new ConcurrentHashMap<>();

  private final ConcurrentMap<TestSuiteDescriptor, TestContext> testSuiteStates =
      new ConcurrentHashMap<>();

  private final TestDecorator testDecorator;

  public TestEventsHandler(TestDecorator testDecorator) {
    this.testDecorator = testDecorator;
  }

  public void onTestModuleStart(final @Nullable String version) {
    final AgentSpan span = startSpan(testDecorator.component() + ".test_module");
    final AgentScope scope = activateSpan(span);
    scope.setAsyncPropagation(true);

    testModuleContext = new TestContext(span);

    testDecorator.afterTestModuleStart(span, version);
  }

  public void onTestModuleFinish() {
    final AgentSpan span = AgentTracer.activeSpan();
    if (!isTestModuleSpan(AgentTracer.activeSpan())) {
      return;
    }

    final AgentScope scope = AgentTracer.activeScope();
    if (scope != null) {
      scope.close();
    }

    span.setTag(Tags.TEST_STATUS, testModuleContext.getStatus());
    span.setTag(Tags.TEST_MODULE_ID, testModuleContext.getId());

    testDecorator.beforeFinish(span);
    span.finish();
  }

  public void onTestSuiteStart(
      final String testSuiteName,
      final @Nullable Class<?> testClass,
      final @Nullable String version,
      final @Nullable Collection<String> categories) {
    if (skipTrace(testClass)) {
      return;
    }

    if (!tryTestSuiteStart(testSuiteName, testClass)) {
      return;
    }

    final AgentSpan span = startSpan(testDecorator.component() + ".test_suite");
    final AgentScope scope = activateSpan(span);
    scope.setAsyncPropagation(true);

    TestSuiteDescriptor testSuiteDescriptor = new TestSuiteDescriptor(testSuiteName, testClass);
    testSuiteStates.put(testSuiteDescriptor, new TestContext(span));

    testDecorator.afterTestSuiteStart(span, testSuiteName, testClass, version, categories);
  }

  public void onTestSuiteFinish(final String testSuiteName, final @Nullable Class<?> testClass) {
    if (skipTrace(testClass)) {
      return;
    }

    if (!tryTestSuiteFinish(testSuiteName, testClass)) {
      return;
    }

    final AgentSpan span = AgentTracer.activeSpan();
    if (!isTestSuiteSpan(AgentTracer.activeSpan())) {
      return;
    }

    final AgentScope scope = AgentTracer.activeScope();
    if (scope != null) {
      scope.close();
    }

    TestSuiteDescriptor testSuiteDescriptor = new TestSuiteDescriptor(testSuiteName, testClass);
    TestContext testSuiteContext = testSuiteStates.remove(testSuiteDescriptor);

    span.setTag(Tags.TEST_STATUS, testSuiteContext.getStatus());
    testModuleContext.reportChildStatus(testSuiteContext.getStatus());

    span.setTag(Tags.TEST_SUITE_ID, testSuiteContext.getId());
    span.setTag(Tags.TEST_MODULE_ID, testModuleContext.getId());

    testDecorator.beforeFinish(span);
    span.finish();
  }

  private boolean tryTestSuiteStart(String testSuiteName, Class<?> testClass) {
    if (testModuleContext == null) {
      // do not create test suite spans for legacy setups that do not create modules
      return false;
    }

    TestSuiteDescriptor testSuiteDescriptor = new TestSuiteDescriptor(testSuiteName, testClass);
    Integer counter = testSuiteNestedCallCounters.merge(testSuiteDescriptor, 1, Integer::sum);
    return counter == 1;
  }

  private boolean tryTestSuiteFinish(String testSuiteName, Class<?> testClass) {
    TestSuiteDescriptor testSuiteDescriptor = new TestSuiteDescriptor(testSuiteName, testClass);
    Integer counter =
        testSuiteNestedCallCounters.merge(
            testSuiteDescriptor, -1, (a, b) -> a + b > 0 ? a + b : null);
    return counter == null;
  }

  public void onSkip(final @Nullable String reason) {
    final AgentSpan span = AgentTracer.activeSpan();
    if (!isTestSuiteSpan(span) && !isTestSpan(span)) {
      return;
    }

    span.setTag(Tags.TEST_STATUS, TEST_SKIP);

    if (reason != null) {
      span.setTag(Tags.TEST_SKIP_REASON, reason);
    }
  }

  public void onFailure(@Nullable final Throwable throwable) {
    if (throwable == null) {
      return;
    }

    final AgentSpan span = AgentTracer.activeSpan();
    if (!isTestSuiteSpan(span) && !isTestSpan(span)) {
      return;
    }

    span.setError(true);
    span.addThrowable(throwable);
    span.setTag(Tags.TEST_STATUS, TEST_FAIL);
  }

  public void onTestStart(
      final String testSuiteName,
      final String testName,
      final String testParameters,
      final Collection<String> categories,
      final String version,
      final Class<?> testClass,
      final Method testMethod) {
    if (skipTrace(testClass)) {
      return;
    }

    // If there is an active span that represents a test
    // we don't want to generate another child test span.
    if (isTestSpan(AgentTracer.activeSpan())) {
      return;
    }

    final AgentSpan span = startSpan(testDecorator.component() + ".test");
    final AgentScope scope = activateSpan(span);
    scope.setAsyncPropagation(true);

    testDecorator.afterTestStart(
        span, testSuiteName, testName, testParameters, version, testClass, testMethod, categories);

    // setting status here optimistically, will rewrite if failure is encountered
    span.setTag(Tags.TEST_STATUS, TEST_PASS);
  }

  public void onTestFinish(final String testSuiteName, final Class<?> testClass) {
    if (skipTrace(testClass)) {
      return;
    }

    final AgentSpan span = AgentTracer.activeSpan();
    if (!isTestSpan(span)) {
      return;
    }

    final AgentScope scope = AgentTracer.activeScope();
    if (scope != null) {
      scope.close();
    }

    beforeTestFinish(testSuiteName, testClass, span);
    testDecorator.beforeFinish(span);
    span.finish();
  }

  private void beforeTestFinish(String testSuiteName, Class<?> testClass, AgentSpan span) {
    TestSuiteDescriptor testSuiteDescriptor = new TestSuiteDescriptor(testSuiteName, testClass);
    TestContext testSuiteContext = testSuiteStates.get(testSuiteDescriptor);
    if (testSuiteContext != null) {
      span.setTag(Tags.TEST_SUITE_ID, testSuiteContext.getId());
      span.setTag(Tags.TEST_MODULE_ID, testModuleContext.getId());

      String testCaseStatus = (String) span.getTag(Tags.TEST_STATUS);
      testSuiteContext.reportChildStatus(testCaseStatus);

    } else {
      // TODO put warning once TestNG support for test suites is implemented
    }
  }

  public void onTestIgnore(
      final String testSuiteName,
      final String testName,
      final String testParameters,
      final List<String> categories,
      final String version,
      final Class<?> testClass,
      final Method testMethod,
      final String reason) {
    if (skipTrace(testClass)) {
      return;
    }

    final AgentSpan span = startSpan("junit.test");
    final AgentScope scope = activateSpan(span);

    testDecorator.afterTestStart(
        span, testSuiteName, testName, testParameters, version, testClass, testMethod, categories);

    onSkip(reason);

    beforeTestFinish(testSuiteName, testClass, span);
    testDecorator.beforeFinish(span);

    scope.close();
    // set duration to 1 ns, because duration==0 has a special treatment
    span.finishWithDuration(1L);
  }

  public boolean isTestSuiteInProgress() {
    return isTestSuiteSpan(AgentTracer.activeSpan());
  }

  private static boolean isTestModuleSpan(final @Nullable AgentSpan activeSpan) {
    return activeSpan != null
        && DDSpanTypes.TEST_MODULE_END.equals(activeSpan.getSpanType())
        && TestDecorator.TEST_TYPE.equals(activeSpan.getTag(Tags.TEST_TYPE));
  }

  private static boolean isTestSuiteSpan(final @Nullable AgentSpan activeSpan) {
    return activeSpan != null
        && DDSpanTypes.TEST_SUITE_END.equals(activeSpan.getSpanType())
        && TestDecorator.TEST_TYPE.equals(activeSpan.getTag(Tags.TEST_TYPE));
  }

  private static boolean isTestSpan(@Nullable final AgentSpan activeSpan) {
    return activeSpan != null
        && DDSpanTypes.TEST.equals(activeSpan.getSpanType())
        && TestDecorator.TEST_TYPE.equals(activeSpan.getTag(Tags.TEST_TYPE));
  }

  private static boolean skipTrace(final @Nullable Class<?> testClass) {
    return testClass != null && testClass.getAnnotation(DisableTestTrace.class) != null;
  }
}
