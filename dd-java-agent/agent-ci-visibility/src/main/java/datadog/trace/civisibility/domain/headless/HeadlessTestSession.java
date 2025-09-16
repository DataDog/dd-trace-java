package datadog.trace.civisibility.domain.headless;

import static datadog.trace.civisibility.domain.SpanTagsPropagator.TagMergeSpec;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.civisibility.config.LibraryCapability;
import datadog.trace.api.civisibility.coverage.CoverageStore;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.tag.Provider;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.domain.AbstractTestSession;
import datadog.trace.civisibility.domain.InstrumentationType;
import datadog.trace.civisibility.domain.TestFrameworkSession;
import datadog.trace.civisibility.source.LinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import datadog.trace.civisibility.test.ExecutionStrategy;
import java.util.Collection;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Test session implementation that is used when the tracer is running in "headless" mode, meaning
 * that only child processes that are forked to execute tests are instrumented, and the parent build
 * system process is NOT instrumented.
 *
 * <p>This class manages the session span since there is no build system instrumentation to do it.
 */
public class HeadlessTestSession extends AbstractTestSession implements TestFrameworkSession {

  private final ExecutionStrategy executionStrategy;
  private final CoverageStore.Factory coverageStoreFactory;
  private final Collection<LibraryCapability> capabilities;

  public HeadlessTestSession(
      String projectName,
      @Nullable Long startTime,
      Provider ciProvider,
      Config config,
      CiVisibilityMetricCollector metricCollector,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver,
      Codeowners codeowners,
      LinesResolver linesResolver,
      CoverageStore.Factory coverageStoreFactory,
      ExecutionStrategy executionStrategy,
      @Nonnull Collection<LibraryCapability> capabilities) {
    super(
        projectName,
        startTime,
        InstrumentationType.HEADLESS,
        ciProvider,
        config,
        metricCollector,
        testDecorator,
        sourcePathResolver,
        codeowners,
        linesResolver);
    this.executionStrategy = executionStrategy;
    this.coverageStoreFactory = coverageStoreFactory;
    this.capabilities = capabilities;
  }

  @Override
  public HeadlessTestModule testModuleStart(String moduleName, @Nullable Long startTime) {
    return new HeadlessTestModule(
        span.context(),
        moduleName,
        startTime,
        config,
        metricCollector,
        testDecorator,
        sourcePathResolver,
        codeowners,
        linesResolver,
        coverageStoreFactory,
        executionStrategy,
        capabilities,
        this::onModuleFinish);
  }

  private void onModuleFinish(AgentSpan moduleSpan) {
    tagPropagator.propagateCiVisibilityTags(moduleSpan);
    tagPropagator.propagateTags(
        moduleSpan,
        TagMergeSpec.of(Tags.TEST_CODE_COVERAGE_ENABLED),
        TagMergeSpec.of(Tags.TEST_ITR_TESTS_SKIPPING_ENABLED),
        TagMergeSpec.of(Tags.TEST_ITR_TESTS_SKIPPING_TYPE),
        TagMergeSpec.of(Tags.TEST_ITR_TESTS_SKIPPING_COUNT),
        TagMergeSpec.of(Tags.TEST_EARLY_FLAKE_ENABLED),
        TagMergeSpec.of(Tags.TEST_EARLY_FLAKE_ABORT_REASON),
        TagMergeSpec.of(DDTags.CI_ITR_TESTS_SKIPPED),
        TagMergeSpec.of(Tags.TEST_TEST_MANAGEMENT_ENABLED),
        TagMergeSpec.of(DDTags.TEST_HAS_FAILED_TEST_REPLAY));
  }

  @Override
  public void end(@Nullable Long endTime) {
    super.end(endTime);
  }
}
