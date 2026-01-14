package datadog.trace.civisibility.domain.manualapi;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.DDTestModule;
import datadog.trace.api.civisibility.coverage.CoverageStore;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.domain.AbstractTestModule;
import datadog.trace.civisibility.domain.InstrumentationType;
import datadog.trace.civisibility.domain.TestSuiteImpl;
import datadog.trace.civisibility.source.LinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import datadog.trace.civisibility.test.ExecutionResults;
import java.util.Collections;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/**
 * Test module that was created using manual API ({@link
 * datadog.trace.api.civisibility.CIVisibility})
 */
public class ManualApiTestModule extends AbstractTestModule implements DDTestModule {

  private final CoverageStore.Factory coverageStoreFactory;
  private final ExecutionResults executionResults = new ExecutionResults();

  public ManualApiTestModule(
      AgentSpanContext sessionSpanContext,
      String moduleName,
      @Nullable Long startTime,
      Config config,
      CiVisibilityMetricCollector metricCollector,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver,
      Codeowners codeowners,
      LinesResolver linesResolver,
      CoverageStore.Factory coverageStoreFactory,
      Consumer<AgentSpan> onSpanFinish) {
    super(
        sessionSpanContext,
        moduleName,
        startTime,
        InstrumentationType.MANUAL_API,
        config,
        metricCollector,
        testDecorator,
        sourcePathResolver,
        codeowners,
        linesResolver,
        onSpanFinish);
    this.coverageStoreFactory = coverageStoreFactory;
  }

  @Override
  public ManualApiTestSuite testSuiteStart(
      String testSuiteName,
      @Nullable Class<?> testClass,
      @Nullable Long startTime,
      boolean parallelized) {
    TestSuiteImpl suite =
        new TestSuiteImpl(
            span.context(),
            moduleName,
            testSuiteName,
            null,
            testClass,
            startTime,
            parallelized,
            InstrumentationType.MANUAL_API,
            TestFrameworkInstrumentation.OTHER, // for metric purposes, framework is OTHER
            config,
            metricCollector,
            testDecorator,
            sourcePathResolver,
            codeowners,
            linesResolver,
            coverageStoreFactory,
            executionResults,
            Collections.emptyList(),
            tagsPropagator::propagateCiVisibilityTags);

    String frameworkName = testDecorator.component().toString();
    suite.setTag(Tags.TEST_FRAMEWORK, frameworkName);

    return new ManualApiTestSuite(suite, frameworkName);
  }
}
