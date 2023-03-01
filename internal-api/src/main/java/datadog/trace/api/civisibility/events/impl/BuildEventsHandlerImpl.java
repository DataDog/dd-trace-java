package datadog.trace.api.civisibility.events.impl;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.decorator.TestDecorator;
import datadog.trace.api.civisibility.events.BuildEventsHandler;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class BuildEventsHandlerImpl<T> implements BuildEventsHandler<T> {

  private final ConcurrentMap<T, SessionContext> testSessionContexts = new ConcurrentHashMap<>();

  private final ConcurrentMap<TestModuleDescriptor<T>, TestContext> testModuleContexts =
      new ConcurrentHashMap<>();

  @Override
  public void onTestSessionStart(
      final T sessionKey,
      final TestDecorator sessionDecorator,
      final String projectName,
      final String startCommand,
      final String buildSystemName,
      final String buildSystemVersion) {
    AgentSpan span = startSpan(sessionDecorator.component() + ".test_session");

    TestContext context = new SpanTestContext(span);
    testSessionContexts.put(sessionKey, new SessionContext(context, sessionDecorator));

    sessionDecorator.afterTestSessionStart(
        span, projectName, startCommand, buildSystemName, buildSystemVersion);
  }

  @Override
  public void onTestFrameworkDetected(
      final T sessionKey, final String frameworkName, final String frameworkVersion) {
    SessionContext sessionContext = testSessionContexts.get(sessionKey);
    AgentSpan span = sessionContext.context.getSpan();
    if (span == null) {
      throw new IllegalStateException("Could not find session span for key: " + sessionKey);
    }

    span.setTag(Tags.TEST_FRAMEWORK, frameworkName);
    span.setTag(Tags.TEST_FRAMEWORK_VERSION, frameworkVersion);
  }

  @Override
  public void onTestSessionFinish(final T sessionKey) {
    SessionContext sessionContext = testSessionContexts.remove(sessionKey);
    AgentSpan span = sessionContext.context.getSpan();
    if (span == null) {
      throw new IllegalStateException("Could not find session span for key: " + sessionKey);
    }

    span.setTag(Tags.TEST_STATUS, sessionContext.context.getStatus());
    span.setTag(Tags.TEST_SESSION_ID, sessionContext.context.getId());

    sessionContext.decorator.beforeFinish(span);
    span.finish();
  }

  @Override
  public ModuleAndSessionId onTestModuleStart(final T sessionKey, final String moduleName) {
    SessionContext sessionContext = testSessionContexts.get(sessionKey);
    AgentSpan sessionSpan = sessionContext.context.getSpan();
    if (sessionSpan == null) {
      throw new IllegalStateException("Could not find session span for key: " + sessionKey);
    }

    AgentSpan span =
        startSpan(sessionContext.decorator.component() + ".test_module", sessionSpan.context());
    // will overwrite in case of skip/failure
    span.setTag(Tags.TEST_STATUS, CIConstants.TEST_PASS);

    TestModuleDescriptor<T> testModuleDescriptor =
        new TestModuleDescriptor<>(sessionKey, moduleName);
    testModuleContexts.put(testModuleDescriptor, new SpanTestContext(span));

    sessionContext.decorator.afterTestModuleStart(span, moduleName, null);

    return new ModuleAndSessionId(span.getSpanId(), sessionSpan.getSpanId());
  }

  @Override
  public void onModuleTestFrameworkDetected(
      final T sessionKey,
      final String moduleName,
      final String frameworkName,
      final String frameworkVersion) {
    AgentSpan span = getTestModuleSpan(sessionKey, moduleName);
    span.setTag(Tags.TEST_FRAMEWORK, frameworkName);
    span.setTag(Tags.TEST_FRAMEWORK_VERSION, frameworkVersion);
  }

  @Override
  public void onTestModuleSkip(final T sessionKey, final String moduleName, final String reason) {
    AgentSpan span = getTestModuleSpan(sessionKey, moduleName);
    span.setTag(Tags.TEST_STATUS, CIConstants.TEST_SKIP);
    span.setTag(Tags.TEST_SKIP_REASON, reason);
  }

  @Override
  public void onTestModuleFail(
      final T sessionKey, final String moduleName, final Throwable throwable) {
    AgentSpan span = getTestModuleSpan(sessionKey, moduleName);
    span.setError(true);
    span.addThrowable(throwable);
    span.setTag(Tags.TEST_STATUS, CIConstants.TEST_FAIL);
  }

  private AgentSpan getTestModuleSpan(final T sessionKey, final String moduleName) {
    TestModuleDescriptor<T> testModuleDescriptor =
        new TestModuleDescriptor<>(sessionKey, moduleName);
    TestContext testModuleContext = testModuleContexts.get(testModuleDescriptor);
    final AgentSpan span = testModuleContext.getSpan();
    if (span == null) {
      throw new IllegalStateException(
          "Could not find module span for session key "
              + sessionKey
              + " and module name "
              + moduleName);
    }
    return span;
  }

  @Override
  public void onTestModuleFinish(T sessionKey, String moduleName) {
    TestModuleDescriptor<T> testModuleDescriptor =
        new TestModuleDescriptor<>(sessionKey, moduleName);
    TestContext testModuleContext = testModuleContexts.remove(testModuleDescriptor);

    final AgentSpan span = testModuleContext.getSpan();
    if (span == null) {
      throw new IllegalStateException(
          "Could not find module span for session key "
              + sessionKey
              + " and module name "
              + moduleName);
    }

    span.setTag(Tags.TEST_MODULE_ID, testModuleContext.getId());

    SessionContext sessionContext = testSessionContexts.get(sessionKey);
    span.setTag(Tags.TEST_SESSION_ID, sessionContext.context.getId());
    sessionContext.context.reportChildStatus(testModuleContext.getStatus());

    sessionContext.decorator.beforeFinish(span);
    span.finish();
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
