package datadog.trace.civisibility;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.DDTestModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.context.SpanTestContext;
import datadog.trace.civisibility.context.TestContext;
import datadog.trace.civisibility.coverage.CoverageProbeStoreFactory;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.source.MethodLinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import javax.annotation.Nullable;

public class DDTestModuleImpl implements DDTestModule {

  private final AgentSpan span;
  protected final String moduleName;
  protected final SpanTestContext context;
  protected final TestContext sessionContext;
  protected final Config config;
  protected final TestDecorator testDecorator;
  protected final SourcePathResolver sourcePathResolver;
  protected final Codeowners codeowners;
  protected final MethodLinesResolver methodLinesResolver;
  protected final CoverageProbeStoreFactory coverageProbeStoreFactory;

  public DDTestModuleImpl(
      TestContext sessionContext,
      String moduleName,
      @Nullable Long startTime,
      Config config,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver,
      Codeowners codeowners,
      MethodLinesResolver methodLinesResolver,
      CoverageProbeStoreFactory coverageProbeStoreFactory) {
    this.sessionContext = sessionContext;
    this.moduleName = moduleName;
    this.config = config;
    this.testDecorator = testDecorator;
    this.sourcePathResolver = sourcePathResolver;
    this.codeowners = codeowners;
    this.methodLinesResolver = methodLinesResolver;
    this.coverageProbeStoreFactory = coverageProbeStoreFactory;

    AgentSpan sessionSpan = sessionContext.getSpan();
    AgentSpan.Context sessionSpanContext = sessionSpan != null ? sessionSpan.context() : null;

    if (startTime != null) {
      span = startSpan(testDecorator.component() + ".test_module", sessionSpanContext, startTime);
    } else {
      span = startSpan(testDecorator.component() + ".test_module", sessionSpanContext);
    }

    context = new SpanTestContext(span, sessionContext);

    span.setSpanType(InternalSpanTypes.TEST_MODULE_END);
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_TEST_MODULE);

    span.setResourceName(moduleName);
    span.setTag(Tags.TEST_MODULE, moduleName);

    span.setTag(Tags.TEST_MODULE_ID, context.getId());
    span.setTag(Tags.TEST_SESSION_ID, sessionContext.getId());

    testDecorator.afterStart(span);
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
    span.setTag(Tags.TEST_STATUS, context.getStatus());
    sessionContext.reportChildStatus(context.getStatus());

    testDecorator.beforeFinish(span);

    if (endTime != null) {
      span.finish(endTime);
    } else {
      span.finish();
    }
  }

  @Override
  public DDTestSuiteImpl testSuiteStart(
      String testSuiteName,
      @Nullable Class<?> testClass,
      @Nullable Long startTime,
      boolean parallelized) {
    return new DDTestSuiteImpl(
        context,
        moduleName,
        testSuiteName,
        testClass,
        startTime,
        config,
        testDecorator,
        sourcePathResolver,
        codeowners,
        methodLinesResolver,
        coverageProbeStoreFactory,
        parallelized);
  }
}
