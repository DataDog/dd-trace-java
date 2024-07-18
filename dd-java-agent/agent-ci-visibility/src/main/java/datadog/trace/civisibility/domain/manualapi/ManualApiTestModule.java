package datadog.trace.civisibility.domain.manualapi;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.DDTestModule;
import datadog.trace.api.civisibility.coverage.CoverageStore;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.civisibility.InstrumentationType;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.domain.AbstractTestModule;
import datadog.trace.civisibility.domain.TestSuiteImpl;
import datadog.trace.civisibility.source.MethodLinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import datadog.trace.civisibility.utils.SpanUtils;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/**
 * Test module that was created using manual API ({@link
 * datadog.trace.api.civisibility.CIVisibility})
 */
public class ManualApiTestModule extends AbstractTestModule implements DDTestModule {

  private final CoverageStore.Factory coverageStoreFactory;

  public ManualApiTestModule(
      AgentSpan.Context sessionSpanContext,
      long sessionId,
      String moduleName,
      @Nullable Long startTime,
      Config config,
      CiVisibilityMetricCollector metricCollector,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver,
      Codeowners codeowners,
      MethodLinesResolver methodLinesResolver,
      CoverageStore.Factory coverageStoreFactory,
      Consumer<AgentSpan> onSpanFinish) {
    super(
        sessionSpanContext,
        sessionId,
        moduleName,
        startTime,
        InstrumentationType.MANUAL_API,
        config,
        metricCollector,
        testDecorator,
        sourcePathResolver,
        codeowners,
        methodLinesResolver,
        onSpanFinish);
    this.coverageStoreFactory = coverageStoreFactory;
  }

  @Override
  public TestSuiteImpl testSuiteStart(
      String testSuiteName,
      @Nullable Class<?> testClass,
      @Nullable Long startTime,
      boolean parallelized) {
    return new TestSuiteImpl(
        span.context(),
        sessionId,
        span.getSpanId(),
        moduleName,
        testSuiteName,
        null,
        testClass,
        startTime,
        parallelized,
        InstrumentationType.MANUAL_API,
        TestFrameworkInstrumentation.OTHER,
        config,
        metricCollector,
        testDecorator,
        sourcePathResolver,
        codeowners,
        methodLinesResolver,
        coverageStoreFactory,
        SpanUtils.propagateCiVisibilityTagsTo(span));
  }
}
