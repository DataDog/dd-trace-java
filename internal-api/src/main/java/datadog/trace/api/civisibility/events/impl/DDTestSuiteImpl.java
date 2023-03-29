package datadog.trace.api.civisibility.events.impl;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.DDTest;
import datadog.trace.api.civisibility.DDTestSuite;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.codeowners.Codeowners;
import datadog.trace.api.civisibility.decorator.TestDecorator;
import datadog.trace.api.civisibility.source.MethodLinesResolver;
import datadog.trace.api.civisibility.source.SourcePathResolver;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.lang.reflect.Method;
import javax.annotation.Nullable;

public class DDTestSuiteImpl implements DDTestSuite {

  private final AgentSpan span;
  private final TestContext context;
  private final TestContext moduleContext;
  private final Class<?> testClass;
  private final TestDecorator testDecorator;

  public DDTestSuiteImpl(
      TestContext moduleContext,
      String modulePath,
      String testSuiteName,
      Class<?> testClass,
      @Nullable Long startTime,
      Config config,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver) {
    this.testDecorator = testDecorator;

    this.moduleContext = moduleContext;
    AgentSpan moduleSpan = this.moduleContext.getSpan();
    AgentSpan.Context moduleSpanContext = moduleSpan != null ? moduleSpan.context() : null;

    if (startTime != null) {
      span = startSpan(testDecorator.component() + ".test_suite", moduleSpanContext, startTime);
    } else {
      span = startSpan(testDecorator.component() + ".test_suite", moduleSpanContext);
    }

    context = new SpanTestContext(span);

    span.setSpanType(InternalSpanTypes.TEST_SUITE_END);
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_TEST_SUITE);

    span.setResourceName(testSuiteName);
    span.setTag(Tags.TEST_SUITE, testSuiteName);
    span.setTag(Tags.TEST_MODULE, modulePath);

    span.setTag(Tags.TEST_SUITE_ID, context.getId());
    span.setTag(Tags.TEST_MODULE_ID, moduleContext.getId());
    span.setTag(Tags.TEST_SESSION_ID, moduleContext.getParentId());

    this.testClass = testClass;
    if (this.testClass != null) {
      if (config.isCiVisibilitySourceDataEnabled()) {
        String sourcePath = sourcePathResolver.getSourcePath(testClass);
        if (sourcePath != null && !sourcePath.isEmpty()) {
          span.setTag(Tags.TEST_SOURCE_FILE, sourcePath);
        }
      }
    }

    testDecorator.afterStart(span);

    final AgentScope scope = activateSpan(span);
    scope.setAsyncPropagation(true);
  }

  // FIXME move setTag / setErrorInfo / setSkipReason to a common superclass? (they're the same in
  // test, and probably module/session too)

  @Override
  public void setTag(String key, Object value) {
    span.setTag(key, value);
  }

  @Override
  public void setErrorInfo(Throwable error) {
    span.setError(true);
    span.addThrowable(error);
    span.setTag(Tags.TEST_STATUS, CIConstants.TEST_FAIL);
  }

  @Override
  public void setSkipReason(String skipReason) {
    span.setTag(Tags.TEST_STATUS, CIConstants.TEST_SKIP);

    if (skipReason != null) {
      span.setTag(Tags.TEST_SKIP_REASON, skipReason);
    }
  }

  // FIXME also end??? common for test / suite / module, etc

  @Override
  public void end(@Nullable Long endTime) {
    final AgentScope scope = AgentTracer.activeScope();
    if (scope == null) {
      throw new IllegalStateException(
          "No active scope present, it is possible that end() was called multiple times");
    }

    AgentSpan scopeSpan = scope.span();
    if (scopeSpan != span) {
      throw new IllegalStateException(
          "Active scope does not correspond to the finished suite, "
              + "it is possible that end() was called multiple times "
              + "or an operation that was started by the suite is still in progress; "
              + "active scope span is: "
              + scopeSpan);
    }

    scope.close();

    testDecorator.beforeFinish(span);

    if (endTime != null) {
      span.finish(endTime);
    } else {
      span.finish();
    }

    String status = context.getStatus();
    span.setTag(Tags.TEST_STATUS, status);
    moduleContext.reportChildStatus(status);
  }

  @Override
  public DDTest testStart(String testName, @Nullable Method testMethod, @Nullable Long startTime) {
    long suiteId = span.getSpanId();
    Long moduleId = (Long) span.getTag(Tags.TEST_MODULE_ID);
    Long sessionId = (Long) span.getTag(Tags.TEST_SESSION_ID);

    // FIXME ugly injection
    SourcePathResolver sourcePathResolver = testDecorator.getSourcePathResolver();
    Codeowners codeowners = testDecorator.getCodeowners();
    MethodLinesResolver methodLinesResolver = InstrumentationBridge.getMethodLinesResolver();
    return new DDTestImpl(
        context,
        sessionId,
        moduleId,
        suiteId,
        (String) span.getTag(Tags.TEST_MODULE),
        (String) span.getTag(Tags.TEST_SUITE),
        testName,
        startTime,
        testClass,
        testMethod,
        Config.get(), // use config of this suite
        testDecorator,
        sourcePathResolver,
        methodLinesResolver,
        codeowners);
  }
}
