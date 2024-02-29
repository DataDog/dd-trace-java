package datadog.trace.civisibility.domain;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.DDTestSuite;
import datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.tag.EventType;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.InstrumentationType;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.coverage.CoverageProbeStoreFactory;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.source.MethodLinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import datadog.trace.civisibility.utils.SpanUtils;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import javax.annotation.Nullable;

public class TestSuiteImpl implements DDTestSuite {

  private final AgentSpan span;
  private final long sessionId;
  private final long moduleId;
  private final String moduleName;
  private final String testSuiteName;
  private final String itrCorrelationId;
  private final Class<?> testClass;
  private final InstrumentationType instrumentationType;
  private final TestFrameworkInstrumentation instrumentation;
  private final Config config;
  CiVisibilityMetricCollector metricCollector;
  private final TestDecorator testDecorator;
  private final SourcePathResolver sourcePathResolver;
  private final Codeowners codeowners;
  private final MethodLinesResolver methodLinesResolver;
  private final CoverageProbeStoreFactory coverageProbeStoreFactory;
  private final boolean parallelized;
  private final Consumer<AgentSpan> onSpanFinish;

  public TestSuiteImpl(
      @Nullable AgentSpan.Context moduleSpanContext,
      long sessionId,
      long moduleId,
      String moduleName,
      String testSuiteName,
      String itrCorrelationId,
      @Nullable Class<?> testClass,
      @Nullable Long startTime,
      boolean parallelized,
      InstrumentationType instrumentationType,
      TestFrameworkInstrumentation instrumentation,
      Config config,
      CiVisibilityMetricCollector metricCollector,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver,
      Codeowners codeowners,
      MethodLinesResolver methodLinesResolver,
      CoverageProbeStoreFactory coverageProbeStoreFactory,
      Consumer<AgentSpan> onSpanFinish) {
    this.sessionId = sessionId;
    this.moduleId = moduleId;
    this.moduleName = moduleName;
    this.testSuiteName = testSuiteName;
    this.itrCorrelationId = itrCorrelationId;
    this.parallelized = parallelized;
    this.instrumentationType = instrumentationType;
    this.instrumentation = instrumentation;
    this.config = config;
    this.metricCollector = metricCollector;
    this.testDecorator = testDecorator;
    this.sourcePathResolver = sourcePathResolver;
    this.codeowners = codeowners;
    this.methodLinesResolver = methodLinesResolver;
    this.coverageProbeStoreFactory = coverageProbeStoreFactory;
    this.onSpanFinish = onSpanFinish;

    if (startTime != null) {
      span = startSpan(testDecorator.component() + ".test_suite", moduleSpanContext, startTime);
    } else {
      span = startSpan(testDecorator.component() + ".test_suite", moduleSpanContext);
    }

    span.setSpanType(InternalSpanTypes.TEST_SUITE_END);
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_TEST_SUITE);

    span.setResourceName(testSuiteName);
    span.setTag(Tags.TEST_SUITE, testSuiteName);
    span.setTag(Tags.TEST_MODULE, moduleName);

    span.setTag(Tags.TEST_SUITE_ID, span.getSpanId());
    span.setTag(Tags.TEST_MODULE_ID, moduleId);
    span.setTag(Tags.TEST_SESSION_ID, sessionId);

    // setting status to skip initially,
    // as we do not know in advance whether the suite will have any children
    span.setTag(Tags.TEST_STATUS, CIConstants.TEST_SKIP);

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

    metricCollector.add(CiVisibilityCountMetric.EVENT_CREATED, 1, instrumentation, EventType.SUITE);

    if (instrumentationType == InstrumentationType.MANUAL_API) {
      metricCollector.add(CiVisibilityCountMetric.MANUAL_API_EVENTS, 1, EventType.SUITE);
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

    onSpanFinish.accept(span);

    if (endTime != null) {
      span.finish(endTime);
    } else {
      span.finish();
    }

    metricCollector.add(
        CiVisibilityCountMetric.EVENT_FINISHED, 1, instrumentation, EventType.SUITE);
  }

  @Override
  public TestImpl testStart(
      String testName, @Nullable Method testMethod, @Nullable Long startTime) {
    return new TestImpl(
        sessionId,
        moduleId,
        span.getSpanId(),
        moduleName,
        testSuiteName,
        testName,
        itrCorrelationId,
        startTime,
        testClass,
        testMethod,
        instrumentationType,
        instrumentation,
        config,
        metricCollector,
        testDecorator,
        sourcePathResolver,
        methodLinesResolver,
        codeowners,
        coverageProbeStoreFactory,
        SpanUtils.propagateCiVisibilityTagsTo(span));
  }
}
