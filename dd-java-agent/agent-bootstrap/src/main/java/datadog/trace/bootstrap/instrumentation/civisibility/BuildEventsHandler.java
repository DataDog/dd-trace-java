package datadog.trace.bootstrap.instrumentation.civisibility;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.api.DDSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.decorator.TestDecorator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

// FIXME use Continuation for session and module spans?
public class BuildEventsHandler<T> {

  private final ConcurrentMap<T, TestContext> testSessionContexts = new ConcurrentHashMap<>();

  private final ConcurrentMap<TestModuleDescriptor<T>, TestContext> testModuleContexts =
      new ConcurrentHashMap<>();

  private final TestDecorator testDecorator;

  public BuildEventsHandler(TestDecorator testDecorator) {
    this.testDecorator = testDecorator;
  }

  public void onTestSessionStart(T sessionKey) {
    final AgentSpan span = startSpan(testDecorator.component() + ".test_session");
    final AgentScope scope = activateSpan(span);
    scope.setAsyncPropagation(true);

    testSessionContexts.put(sessionKey, new SpanTestContext(span));

    testDecorator.afterTestSessionStart(span);
  }

  public void onTestSessionFinish(T sessionKey) {
    final AgentSpan span = AgentTracer.activeSpan();
    if (!isTestSessionSpan(AgentTracer.activeSpan())) {
      return;
    }

    final AgentScope scope = AgentTracer.activeScope();
    if (scope != null) {
      scope.close();
    }

    TestContext testSessionContext = testSessionContexts.remove(sessionKey);
    span.setTag(Tags.TEST_STATUS, testSessionContext.getStatus());
    span.setTag(Tags.TEST_SESSION_ID, testSessionContext.getId());

    testDecorator.beforeFinish(span);
    span.finish();
  }

  public TestContext onTestModuleStart(T sessionKey, String moduleName) {
    final AgentSpan span = startSpan(testDecorator.component() + ".test_module");
    span.setTag(
        Tags.TEST_STATUS, TestEventsHandler.TEST_PASS); // will overwrite in case of skip/failure

    final AgentScope scope = activateSpan(span);
    scope.setAsyncPropagation(true);

    TestContext testSessionContext = testSessionContexts.get(sessionKey);
    TestContext testModuleContext = new SpanTestContext(span, testSessionContext.getId());
    TestModuleDescriptor<T> testModuleDescriptor =
        new TestModuleDescriptor<>(sessionKey, moduleName);
    testModuleContexts.put(testModuleDescriptor, testModuleContext);

    // FIXME determine framework (see testDecorator.component())
    // FIXME determine version (see NULL parameter below)
    testDecorator.afterTestModuleStart(span, moduleName, null);

    return testModuleContext;
  }

  public void onTestModuleSkip(T sessionKey, String moduleName, String reason) {
    final AgentSpan span = AgentTracer.activeSpan();
    if (!isTestModuleSpan(span)) {
      return;
    }

    span.setTag(Tags.TEST_STATUS, TestEventsHandler.TEST_SKIP);
    span.setTag(Tags.TEST_SKIP_REASON, reason);
  }

  public void onTestModuleFail(T sessionKey, String moduleName, Throwable throwable) {
    final AgentSpan span = AgentTracer.activeSpan();
    if (!isTestModuleSpan(span)) {
      return;
    }

    span.setError(true);
    span.addThrowable(throwable);
    span.setTag(Tags.TEST_STATUS, TestEventsHandler.TEST_FAIL);
  }

  public void onTestModuleFinish(T sessionKey, String moduleName) {
    final AgentSpan span = AgentTracer.activeSpan();
    if (!isTestModuleSpan(span)) {
      return;
    }

    final AgentScope scope = AgentTracer.activeScope();
    if (scope != null) {
      scope.close();
    }

    TestModuleDescriptor<T> testModuleDescriptor =
        new TestModuleDescriptor<>(sessionKey, moduleName);
    TestContext testModuleContext = testModuleContexts.remove(testModuleDescriptor);
    span.setTag(Tags.TEST_MODULE_ID, testModuleContext.getId());

    TestContext testSessionContext = testSessionContexts.get(sessionKey);
    span.setTag(Tags.TEST_SESSION_ID, testSessionContext.getId());
    testSessionContext.reportChildStatus(testModuleContext.getStatus());

    testDecorator.beforeFinish(span);
    span.finish();
  }

  private static boolean isTestModuleSpan(final AgentSpan activeSpan) {
    return activeSpan != null
        && DDSpanTypes.TEST_MODULE_END.equals(activeSpan.getSpanType())
        && TestDecorator.TEST_TYPE.equals(activeSpan.getTag(Tags.TEST_TYPE));
  }

  private static boolean isTestSessionSpan(final AgentSpan activeSpan) {
    return activeSpan != null
        && DDSpanTypes.TEST_SESSION_END.equals(activeSpan.getSpanType())
        && TestDecorator.TEST_TYPE.equals(activeSpan.getTag(Tags.TEST_TYPE));
  }
}
