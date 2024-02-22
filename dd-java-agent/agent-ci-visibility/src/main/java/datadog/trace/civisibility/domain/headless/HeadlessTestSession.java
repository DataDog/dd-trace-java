package datadog.trace.civisibility.domain.headless;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.InstrumentationType;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.coverage.CoverageProbeStoreFactory;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.domain.AbstractTestSession;
import datadog.trace.civisibility.domain.TestFrameworkSession;
import datadog.trace.civisibility.source.MethodLinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import datadog.trace.civisibility.utils.SpanUtils;
import javax.annotation.Nullable;

/**
 * Test session implementation that is used when the tracer is running in "headless" mode, meaning
 * that only child processes that are forked to execute tests are instrumented, and the parent build
 * system process is NOT instrumented.
 *
 * <p>This class manages the session span since there is no build system instrumentation to do it.
 */
public class HeadlessTestSession extends AbstractTestSession implements TestFrameworkSession {

  private final ModuleExecutionSettings moduleExecutionSettings;

  public HeadlessTestSession(
      String projectName,
      @Nullable Long startTime,
      boolean supportedCiProvider,
      Config config,
      CiVisibilityMetricCollector metricCollector,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver,
      Codeowners codeowners,
      MethodLinesResolver methodLinesResolver,
      CoverageProbeStoreFactory coverageProbeStoreFactory,
      ModuleExecutionSettings moduleExecutionSettings) {
    super(
        projectName,
        startTime,
        InstrumentationType.HEADLESS,
        supportedCiProvider,
        config,
        metricCollector,
        testDecorator,
        sourcePathResolver,
        codeowners,
        methodLinesResolver,
        coverageProbeStoreFactory);
    this.moduleExecutionSettings = moduleExecutionSettings;
  }

  @Override
  public HeadlessTestModule testModuleStart(String moduleName, @Nullable Long startTime) {
    return new HeadlessTestModule(
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
        coverageProbeStoreFactory,
        moduleExecutionSettings,
        this::propagateModuleTags);
  }

  private void propagateModuleTags(AgentSpan moduleSpan) {
    SpanUtils.propagateCiVisibilityTags(span, moduleSpan);
    SpanUtils.propagateTags(
        span,
        moduleSpan,
        Tags.TEST_CODE_COVERAGE_ENABLED,
        Tags.TEST_ITR_TESTS_SKIPPING_ENABLED,
        Tags.TEST_ITR_TESTS_SKIPPING_TYPE,
        Tags.TEST_ITR_TESTS_SKIPPING_COUNT,
        DDTags.CI_ITR_TESTS_SKIPPED);
  }
}
