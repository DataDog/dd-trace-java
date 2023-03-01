package datadog.trace.bootstrap.instrumentation.civisibility;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.api.Config;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestEventsHandler {

  private static final Logger log = LoggerFactory.getLogger(TestEventsHandler.class);

  private volatile TestContext testModuleContext;

  private final ConcurrentMap<TestSuiteDescriptor, Integer> testSuiteNestedCallCounters =
      new ConcurrentHashMap<>();

  private final ConcurrentMap<TestSuiteDescriptor, TestContext> testSuiteContexts =
      new ConcurrentHashMap<>();

  private final TestDecorator testDecorator;

  public TestEventsHandler(TestDecorator testDecorator) {
    this.testDecorator = testDecorator;

    Config config = Config.get();
    Long sessionId = config.getCiVisibilitySessionId();
    Long moduleId = config.getCiVisibilityModuleId();
    if (sessionId != null && moduleId != null) {
      testModuleContext = new ParentProcessTestContext(sessionId, moduleId);
    }
  }

  public void onTestModuleStart(final @Nullable String version) {
    if (testModuleContext != null) {
      // do not create test module span if parent process provides module data
      return;
    }

    final AgentSpan span = startSpan(testDecorator.component() + ".test_module");
    testModuleContext = new SpanTestContext(span);
    testDecorator.afterTestModuleStart(span, null, version);
  }

  public void onTestModuleFinish() {
    if (!testModuleContext.isLocalToCurrentProcess()) {
      // do not create test module span if parent process provides module data
      return;
    }

    final AgentSpan span = testModuleContext.getSpan();
    if (span == null) {
      throw new IllegalStateException(
          "Test module context is local to current process, but has no span: " + testModuleContext);
    }

    span.setTag(Tags.TEST_STATUS, testModuleContext.getStatus());
    span.setTag(Tags.TEST_MODULE_ID, testModuleContext.getId());
    span.setTag(Tags.TEST_SESSION_ID, testModuleContext.getParentId());

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

    AgentSpan moduleSpan = testModuleContext.getSpan();
    AgentSpan.Context moduleSpanContext = moduleSpan != null ? moduleSpan.context() : null;

    final AgentSpan span = startSpan(testDecorator.component() + ".test_suite", moduleSpanContext);
    final AgentScope scope = activateSpan(span);
    scope.setAsyncPropagation(true);

    TestSuiteDescriptor testSuiteDescriptor = new TestSuiteDescriptor(testSuiteName, testClass);
    testSuiteContexts.put(testSuiteDescriptor, new SpanTestContext(span));

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
    TestContext testSuiteContext = testSuiteContexts.remove(testSuiteDescriptor);

    span.setTag(Tags.TEST_STATUS, testSuiteContext.getStatus());
    testModuleContext.reportChildStatus(testSuiteContext.getStatus());

    span.setTag(Tags.TEST_SUITE_ID, testSuiteContext.getId());
    span.setTag(Tags.TEST_MODULE_ID, testModuleContext.getId());
    span.setTag(Tags.TEST_SESSION_ID, testModuleContext.getParentId());

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

    span.setTag(Tags.TEST_STATUS, Constants.TEST_SKIP);

    if (reason != null) {
      span.setTag(Tags.TEST_SKIP_REASON, reason);
    }
  }

  public void onFailure(final @Nullable Throwable throwable) {
    if (throwable == null) {
      return;
    }

    final AgentSpan span = AgentTracer.activeSpan();
    if (!isTestSuiteSpan(span) && !isTestSpan(span)) {
      return;
    }

    span.setError(true);
    span.addThrowable(throwable);
    span.setTag(Tags.TEST_STATUS, Constants.TEST_FAIL);
  }

  public void onTestStart(
      final String testSuiteName,
      final String testName,
      final @Nullable String testParameters,
      final @Nullable Collection<String> categories,
      final @Nullable String version,
      final @Nullable Class<?> testClass,
      final @Nullable Method testMethod) {
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
    span.setTag(Tags.TEST_STATUS, Constants.TEST_PASS);
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
    TestContext testSuiteContext = testSuiteContexts.get(testSuiteDescriptor);
    if (testSuiteContext != null) {
      span.setTag(Tags.TEST_SUITE_ID, testSuiteContext.getId());
      span.setTag(Tags.TEST_MODULE_ID, testModuleContext.getId());
      span.setTag(Tags.TEST_SESSION_ID, testModuleContext.getParentId());

      String testCaseStatus = (String) span.getTag(Tags.TEST_STATUS);
      testSuiteContext.reportChildStatus(testCaseStatus);

    } else {
      log.debug("Could not find test suite for name {} and class {}", testSuiteName, testClass);
    }
  }

  public void onTestIgnore(
      final String testSuiteName,
      final String testName,
      final @Nullable String testParameters,
      final @Nullable List<String> categories,
      final @Nullable String version,
      final @Nullable Class<?> testClass,
      final @Nullable Method testMethod,
      final @Nullable String reason) {
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

  private static boolean isTestSuiteSpan(final AgentSpan activeSpan) {
    return activeSpan != null
        && DDSpanTypes.TEST_SUITE_END.equals(activeSpan.getSpanType())
        && TestDecorator.TEST_TYPE.equals(activeSpan.getTag(Tags.TEST_TYPE));
  }

  private static boolean isTestSpan(final AgentSpan activeSpan) {
    return activeSpan != null
        && DDSpanTypes.TEST.equals(activeSpan.getSpanType())
        && TestDecorator.TEST_TYPE.equals(activeSpan.getTag(Tags.TEST_TYPE));
  }

  private static boolean skipTrace(final Class<?> testClass) {
    return testClass != null && testClass.getAnnotation(DisableTestTrace.class) != null;
  }
}
