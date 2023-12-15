package datadog.trace.civisibility;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.config.ModuleExecutionSettingsFactory;
import datadog.trace.civisibility.coverage.CoverageProbeStoreFactory;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.source.MethodLinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import datadog.trace.civisibility.utils.SpanUtils;
import javax.annotation.Nullable;

/**
 * Test session implementation that is used by test framework instrumentations only in cases when
 * the build system is NOT instrumented. This class manages the session span since there is no build
 * system instrumentation to do it
 */
public class DDTestFrameworkSessionImpl extends DDTestSessionImpl
    implements DDTestFrameworkSession {

  private final ModuleExecutionSettingsFactory moduleExecutionSettingsFactory;

  public DDTestFrameworkSessionImpl(
      String projectName,
      @Nullable Long startTime,
      Config config,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver,
      Codeowners codeowners,
      MethodLinesResolver methodLinesResolver,
      CoverageProbeStoreFactory coverageProbeStoreFactory,
      ModuleExecutionSettingsFactory moduleExecutionSettingsFactory) {
    super(
        projectName,
        startTime,
        config,
        testDecorator,
        sourcePathResolver,
        codeowners,
        methodLinesResolver,
        coverageProbeStoreFactory);
    this.moduleExecutionSettingsFactory = moduleExecutionSettingsFactory;
  }

  @Override
  public DDTestFrameworkModuleImpl testModuleStart(String moduleName, @Nullable Long startTime) {
    return new DDTestFrameworkModuleImpl(
        span.context(),
        span.getSpanId(),
        moduleName,
        startTime,
        config,
        testDecorator,
        sourcePathResolver,
        codeowners,
        methodLinesResolver,
        coverageProbeStoreFactory,
        moduleExecutionSettingsFactory,
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
