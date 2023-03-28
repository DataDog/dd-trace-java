package datadog.trace.api.civisibility.events.impl;

import datadog.trace.api.Config;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DisableTestTrace;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.decorator.TestDecorator;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.config.CiVisibilityConfig;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.util.Strings;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

public class TestEventsHandlerImpl implements TestEventsHandler {

  private static final Logger log = LoggerFactory.getLogger(TestEventsHandlerImpl.class);

  private volatile TestContext testModuleContext;

  private final ConcurrentMap<TestSuiteDescriptor, Integer> testSuiteNestedCallCounters =
      new ConcurrentHashMap<>();

  private final ConcurrentMap<TestSuiteDescriptor, TestContext> testSuiteContexts =
      new ConcurrentHashMap<>();

  private final TestDecorator testDecorator;

  public TestEventsHandlerImpl(TestDecorator testDecorator) {
    this.testDecorator = testDecorator;

    Config config = Config.get();
    Long sessionId = config.getCiVisibilitySessionId();
    Long moduleId = config.getCiVisibilityModuleId();
    if (sessionId != null && moduleId != null) {
      testModuleContext = new ParentProcessTestContext(sessionId, moduleId);
    }
  }

  @Override
  public void onTestModuleStart(final @Nullable String version) {
    // fallbacks to System.getProperty below are needed for cases when
    // system variables are set after config was initialized

    Long sessionId = Config.get().getCiVisibilitySessionId();
    if (sessionId == null) {
      String systemProp =
          System.getProperty(
              Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_SESSION_ID));
      if (systemProp != null) {
        sessionId = Long.parseLong(systemProp);
      }
    }

    Long moduleId = Config.get().getCiVisibilityModuleId();
    if (moduleId == null) {
      String systemProp =
          System.getProperty(
              Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_MODULE_ID));
      if (systemProp != null) {
        moduleId = Long.parseLong(systemProp);
      }
    }

    if (sessionId != null && moduleId != null) {
      testModuleContext = new ParentProcessTestContext(sessionId, moduleId);

    } else {
      final AgentSpan span = startSpan(testDecorator.component() + ".test_module");
      testModuleContext = new SpanTestContext(span);
      testDecorator.afterTestModuleStart(span, null, version, null);
    }
  }

  @Override
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

  @Override
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

  @Override
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

  @Override
  public void onSkip(final @Nullable String reason) {
    final AgentSpan span = AgentTracer.activeSpan();
    if (!isTestSuiteSpan(span) && !isTestSpan(span)) {
      return;
    }

    span.setTag(Tags.TEST_STATUS, CIConstants.TEST_SKIP);

    if (reason != null) {
      span.setTag(Tags.TEST_SKIP_REASON, reason);
    }
  }

  @Override
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
    span.setTag(Tags.TEST_STATUS, CIConstants.TEST_FAIL);
  }

  @Override
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

    final AgentSpan span = AgentTracer.get().buildSpan(testDecorator.component() + ".test")
        .asChildOf(null)
        .withRequestContextData(RequestContextSlot.CI_VISIBILITY, new TestCoverageProbes())
        .start();

    final AgentScope scope = activateSpan(span);
    scope.setAsyncPropagation(true);

    testDecorator.afterTestStart(
        span, testSuiteName, testName, testParameters, version, testClass, testMethod, categories);

    // setting status here optimistically, will rewrite if failure is encountered
    span.setTag(Tags.TEST_STATUS, CIConstants.TEST_PASS);
  }

  @Override
  public void onTestFinish(final String testSuiteName, final Class<?> testClass) {
    if (skipTrace(testClass)) {
      return;
    }

    final AgentSpan span = AgentTracer.activeSpan();
    if (!isTestSpan(span)) {
      return;
    }

    TestCoverageProbes probes = span.getRequestContext().getData(RequestContextSlot.CI_VISIBILITY);
    probes.report();

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

  @Override
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

    final AgentSpan span = startSpan("junit.test", null);
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

  @Override
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
