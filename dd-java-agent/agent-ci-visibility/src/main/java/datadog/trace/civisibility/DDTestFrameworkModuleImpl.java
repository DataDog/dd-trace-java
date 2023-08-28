package datadog.trace.civisibility;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import datadog.trace.api.civisibility.config.SkippableTest;
import datadog.trace.api.config.CiVisibilityConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.config.JvmInfo;
import datadog.trace.civisibility.config.ModuleExecutionSettingsFactory;
import datadog.trace.civisibility.coverage.CoverageProbeStoreFactory;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.source.MethodLinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/**
 * Test module implementation that is used by test framework instrumentations only in cases when the
 * build system is NOT instrumented: This class manages the module span since there is no build
 * system instrumentation to do it
 */
public class DDTestFrameworkModuleImpl extends DDTestModuleImpl implements DDTestFrameworkModule {

  private final ModuleExecutionSettingsFactory moduleExecutionSettingsFactory;
  private final LongAdder testsSkipped = new LongAdder();
  private final Object skippableTestsInitLock = new Object();
  private volatile Collection<SkippableTest> skippableTests;
  private volatile boolean codeCoverageEnabled;
  private volatile boolean itrEnabled;

  public DDTestFrameworkModuleImpl(
      AgentSpan.Context sessionSpanContext,
      long sessionId,
      String moduleName,
      @Nullable Long startTime,
      Config config,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver,
      Codeowners codeowners,
      MethodLinesResolver methodLinesResolver,
      CoverageProbeStoreFactory coverageProbeStoreFactory,
      ModuleExecutionSettingsFactory moduleExecutionSettingsFactory,
      Consumer<AgentSpan> onSpanFinish) {
    super(
        sessionSpanContext,
        sessionId,
        moduleName,
        startTime,
        config,
        testDecorator,
        sourcePathResolver,
        codeowners,
        methodLinesResolver,
        coverageProbeStoreFactory,
        onSpanFinish);
    this.moduleExecutionSettingsFactory = moduleExecutionSettingsFactory;
  }

  @Override
  public boolean skip(SkippableTest test) {
    if (test == null) {
      return false;
    }

    if (skippableTests == null) {
      synchronized (skippableTestsInitLock) {
        if (skippableTests == null) {
          skippableTests = fetchSkippableTests();
        }
      }
    }

    if (skippableTests.contains(test)) {
      testsSkipped.increment();
      return true;
    } else {
      return false;
    }
  }

  private Collection<SkippableTest> fetchSkippableTests() {
    ModuleExecutionSettings moduleExecutionSettings =
        moduleExecutionSettingsFactory.create(JvmInfo.CURRENT_JVM, moduleName);
    Map<String, String> systemProperties = moduleExecutionSettings.getSystemProperties();
    codeCoverageEnabled =
        propertyEnabled(systemProperties, CiVisibilityConfig.CIVISIBILITY_CODE_COVERAGE_ENABLED);
    itrEnabled = propertyEnabled(systemProperties, CiVisibilityConfig.CIVISIBILITY_ITR_ENABLED);
    return new HashSet<>(moduleExecutionSettings.getSkippableTests(moduleName));
  }

  private boolean propertyEnabled(Map<String, String> systemProperties, String propertyName) {
    String property = systemProperties.get(propertyName);
    return Boolean.parseBoolean(property);
  }

  @Override
  public void end(@Nullable Long endTime) {
    if (codeCoverageEnabled) {
      setTag(Tags.TEST_CODE_COVERAGE_ENABLED, true);
    }

    if (itrEnabled) {
      setTag(Tags.TEST_ITR_TESTS_SKIPPING_ENABLED, true);
      setTag(Tags.TEST_ITR_TESTS_SKIPPING_TYPE, "test");

      long testsSkippedTotal = testsSkipped.sum();
      setTag(Tags.TEST_ITR_TESTS_SKIPPING_COUNT, testsSkippedTotal);
      if (testsSkippedTotal > 0) {
        setTag(DDTags.CI_ITR_TESTS_SKIPPED, true);
      }
    }

    super.end(endTime);
  }
}
