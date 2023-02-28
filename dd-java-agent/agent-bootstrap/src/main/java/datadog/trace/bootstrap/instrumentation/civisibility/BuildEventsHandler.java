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

  private final ConcurrentMap<T, SessionContext> testSessionContexts = new ConcurrentHashMap<>();

  private final ConcurrentMap<TestModuleDescriptor<T>, TestContext> testModuleContexts =
      new ConcurrentHashMap<>();

  public void onTestSessionStart(
      T sessionKey, TestDecorator sessionDecorator, String projectName, String startCommand) {
    final AgentSpan span = startSpan(sessionDecorator.component() + ".test_session");
    final AgentScope scope = activateSpan(span);
    scope.setAsyncPropagation(true);

    TestContext context = new SpanTestContext(span);
    testSessionContexts.put(sessionKey, new SessionContext(context, sessionDecorator));

    sessionDecorator.afterTestSessionStart(span, projectName, startCommand);
  }

  public void onTestFrameworkDetected(T sessionKey, String frameworkName, String frameworkVersion) {
    final AgentSpan span = AgentTracer.activeSpan();
    if (!isTestSessionSpan(AgentTracer.activeSpan())) {
      return;
    }

    span.setTag(Tags.TEST_FRAMEWORK, frameworkName);
    span.setTag(Tags.TEST_FRAMEWORK_VERSION, frameworkVersion);
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

    SessionContext sessionContext = testSessionContexts.remove(sessionKey);
    span.setTag(Tags.TEST_STATUS, sessionContext.context.getStatus());
    span.setTag(Tags.TEST_SESSION_ID, sessionContext.context.getId());

    sessionContext.decorator.beforeFinish(span);
    span.finish();
  }

  public TestContext onTestModuleStart(T sessionKey, String moduleName) {
    SessionContext sessionContext = testSessionContexts.get(sessionKey);

    final AgentSpan span = startSpan(sessionContext.decorator.component() + ".test_module");
    // will overwrite in case of skip/failure
    span.setTag(Tags.TEST_STATUS, TestEventsHandler.TEST_PASS);

    final AgentScope scope = activateSpan(span);
    scope.setAsyncPropagation(true);

    TestContext testModuleContext = new SpanTestContext(span, sessionContext.context.getId());
    TestModuleDescriptor<T> testModuleDescriptor =
        new TestModuleDescriptor<>(sessionKey, moduleName);
    testModuleContexts.put(testModuleDescriptor, testModuleContext);

    // FIXME determine framework (see testDecorator.component())
    // FIXME determine version (see NULL parameter below)
    sessionContext.decorator.afterTestModuleStart(span, moduleName, null);

    return testModuleContext;
  }

  public void onModuleTestFrameworkDetected(
      T sessionKey, String moduleName, String frameworkName, String frameworkVersion) {
    final AgentSpan span = AgentTracer.activeSpan();
    if (!isTestModuleSpan(AgentTracer.activeSpan())) {
      return;
    }

    span.setTag(Tags.TEST_FRAMEWORK, frameworkName);
    span.setTag(Tags.TEST_FRAMEWORK_VERSION, frameworkVersion);
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

    SessionContext sessionContext = testSessionContexts.get(sessionKey);
    span.setTag(Tags.TEST_SESSION_ID, sessionContext.context.getId());
    sessionContext.context.reportChildStatus(testModuleContext.getStatus());

    sessionContext.decorator.beforeFinish(span);
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

  private static final class SessionContext {
    private final TestContext context;
    private final TestDecorator decorator;

    private SessionContext(TestContext context, TestDecorator decorator) {
      this.context = context;
      this.decorator = decorator;
    }
  }
}
