package datadog.trace.civisibility;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.DDTestSuite;
import datadog.trace.api.civisibility.source.SourcePathResolver;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.context.SpanTestContext;
import datadog.trace.civisibility.context.TestContext;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.source.MethodLinesResolver;
import java.lang.reflect.Method;
import javax.annotation.Nullable;

public class DDTestSuiteImpl implements DDTestSuite {

  private final String moduleName;
  private final String testSuiteName;
  private final Class<?> testClass;
  private final AgentSpan span;
  private final TestContext context;
  private final TestContext moduleContext;
  private final Config config;
  private final TestDecorator testDecorator;
  private final SourcePathResolver sourcePathResolver;
  private final Codeowners codeowners;
  private final MethodLinesResolver methodLinesResolver;
  private final boolean parallelized;

  public DDTestSuiteImpl(
      TestContext moduleContext,
      String moduleName,
      String testSuiteName,
      @Nullable Class<?> testClass,
      @Nullable Long startTime,
      Config config,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver,
      Codeowners codeowners,
      MethodLinesResolver methodLinesResolver,
      boolean parallelized) {
    this.moduleName = moduleName;
    this.moduleContext = moduleContext;
    this.testSuiteName = testSuiteName;
    this.config = config;
    this.testDecorator = testDecorator;
    this.sourcePathResolver = sourcePathResolver;
    this.codeowners = codeowners;
    this.methodLinesResolver = methodLinesResolver;
    this.parallelized = parallelized;

    AgentSpan moduleSpan = this.moduleContext.getSpan();
    AgentSpan.Context moduleSpanContext = moduleSpan != null ? moduleSpan.context() : null;

    if (startTime != null) {
      span = startSpan(testDecorator.component() + ".test_suite", moduleSpanContext, startTime);
    } else {
      span = startSpan(testDecorator.component() + ".test_suite", moduleSpanContext);
    }

    context = new SpanTestContext(span, moduleContext);

    span.setSpanType(InternalSpanTypes.TEST_SUITE_END);
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_TEST_SUITE);

    span.setResourceName(testSuiteName);
    span.setTag(Tags.TEST_SUITE, testSuiteName);
    span.setTag(Tags.TEST_MODULE, moduleName);

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

    if (!parallelized) {
      final AgentScope scope = activateSpan(span);
      scope.setAsyncPropagation(true);
    }
  }

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

  @Override
  public void end(@Nullable Long endTime) {
    if (!parallelized) {
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
    }

    testDecorator.beforeFinish(span);

    String status = context.getStatus();
    if (status != null) {
      // do not report test suite if no execution took place
      span.setTag(Tags.TEST_STATUS, status);
      moduleContext.reportChildStatus(status);

      if (endTime != null) {
        span.finish(endTime);
      } else {
        span.finish();
      }
    }

    moduleContext.reportChildTag(Tags.TEST_FRAMEWORK, span.getTag(Tags.TEST_FRAMEWORK));
    moduleContext.reportChildTag(
        Tags.TEST_FRAMEWORK_VERSION, span.getTag(Tags.TEST_FRAMEWORK_VERSION));
  }

  @Override
  public DDTestImpl testStart(
      String testName, @Nullable Method testMethod, @Nullable Long startTime) {
    return testStart(
        testName, testMethod != null ? testMethod.getName() : null, testMethod, startTime);
  }

  public DDTestImpl testStart(
      String testName,
      @Nullable String methodName,
      @Nullable Method testMethod,
      @Nullable Long startTime) {
    return new DDTestImpl(
        context,
        moduleContext,
        moduleName,
        testSuiteName,
        testName,
        startTime,
        testClass,
        methodName,
        testMethod,
        config,
        testDecorator,
        sourcePathResolver,
        methodLinesResolver,
        codeowners);
  }
}
