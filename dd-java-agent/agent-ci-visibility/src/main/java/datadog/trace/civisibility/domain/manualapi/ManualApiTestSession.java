package datadog.trace.civisibility.domain.manualapi;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.DDTestSession;
import datadog.trace.api.civisibility.coverage.CoverageStore;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.tag.Provider;
import datadog.trace.civisibility.InstrumentationType;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.domain.AbstractTestSession;
import datadog.trace.civisibility.source.MethodLinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import datadog.trace.civisibility.utils.SpanUtils;
import javax.annotation.Nullable;

/**
 * Test session that was created using manual API ({@link
 * datadog.trace.api.civisibility.CIVisibility})
 */
public class ManualApiTestSession extends AbstractTestSession implements DDTestSession {

  private final CoverageStore.Factory coverageStoreFactory;

  public ManualApiTestSession(
      String projectName,
      @Nullable Long startTime,
      Provider ciProvider,
      Config config,
      CiVisibilityMetricCollector metricCollector,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver,
      Codeowners codeowners,
      MethodLinesResolver methodLinesResolver,
      CoverageStore.Factory coverageStoreFactory) {
    super(
        projectName,
        startTime,
        InstrumentationType.MANUAL_API,
        ciProvider,
        config,
        metricCollector,
        testDecorator,
        sourcePathResolver,
        codeowners,
        methodLinesResolver);
    this.coverageStoreFactory = coverageStoreFactory;
  }

  @Override
  public ManualApiTestModule testModuleStart(String moduleName, @Nullable Long startTime) {
    return new ManualApiTestModule(
        span.context(),
        span.getSpanId(),
        moduleName,
        startTime,
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
