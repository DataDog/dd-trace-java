package datadog.trace.civisibility;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import datadog.trace.api.civisibility.config.SkippableTest;
import datadog.trace.api.config.CiVisibilityConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.config.JvmInfo;
import datadog.trace.civisibility.config.ModuleExecutionSettingsFactory;
import datadog.trace.civisibility.context.SpanTestContext;
import datadog.trace.civisibility.context.TestContext;
import datadog.trace.civisibility.coverage.CoverageProbeStoreFactory;
import datadog.trace.civisibility.coverage.CoverageUtils;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.ipc.ModuleExecutionResult;
import datadog.trace.civisibility.source.MethodLinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import datadog.trace.civisibility.source.index.RepoIndexProvider;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import javax.annotation.Nullable;
import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.data.ExecutionDataStore;

/**
 * Representation of a test module in a parent process:
 *
 * <ul>
 *   <li>JVM that runs the build system, if build system is instrumented
 *   <li>JVM that runs the tests, if build system is not instrumented
 * </ul>
 */
public class DDTestModuleParent extends DDTestModuleImpl {

  private final AgentSpan span;
  private final SpanTestContext context;
  private final TestContext sessionContext;
  private final String repoRoot;
  private final Collection<File> outputClassesDirs;
  private final ModuleExecutionSettingsFactory moduleExecutionSettingsFactory;
  private final RepoIndexProvider repoIndexProvider;
  private volatile boolean codeCoverageEnabled;
  private volatile boolean itrEnabled;

  public DDTestModuleParent(
      TestContext sessionContext,
      String moduleName,
      String repoRoot,
      @Nullable String startCommand,
      @Nullable Long startTime,
      Collection<File> outputClassesDirs,
      Config config,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver,
      Codeowners codeowners,
      MethodLinesResolver methodLinesResolver,
      ModuleExecutionSettingsFactory moduleExecutionSettingsFactory,
      RepoIndexProvider repoIndexProvider,
      CoverageProbeStoreFactory coverageProbeStoreFactory,
      @Nullable InetSocketAddress signalServerAddress) {
    super(
        moduleName,
        config,
        testDecorator,
        sourcePathResolver,
        codeowners,
        methodLinesResolver,
        coverageProbeStoreFactory,
        signalServerAddress);
    this.sessionContext = sessionContext;
    this.repoRoot = repoRoot;
    this.outputClassesDirs = outputClassesDirs;
    this.moduleExecutionSettingsFactory = moduleExecutionSettingsFactory;
    this.repoIndexProvider = repoIndexProvider;

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

    if (startCommand != null) {
      span.setTag(Tags.TEST_COMMAND, startCommand);
    }

    testDecorator.afterStart(span);
  }

  @Override
  protected SpanTestContext getContext() {
    return context;
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

    testDecorator.beforeFinish(span);

    if (endTime != null) {
      span.finish(endTime);
    } else {
      span.finish();
    }
  }

  @Override
  protected Collection<SkippableTest> fetchSkippableTests() {
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

  public void onModuleExecutionResultReceived(
      ModuleExecutionResult result, ExecutionDataStore coverageData) {
    codeCoverageEnabled = result.isCoverageEnabled();
    itrEnabled = result.isItrEnabled();
    testsSkipped.add(result.getTestsSkippedTotal());

    String testFramework = result.getTestFramework();
    if (testFramework != null) {
      span.setTag(Tags.TEST_FRAMEWORK, testFramework);
    }

    String testFrameworkVersion = result.getTestFrameworkVersion();
    if (testFrameworkVersion != null) {
      span.setTag(Tags.TEST_FRAMEWORK_VERSION, testFrameworkVersion);
    }

    processCoverageData(coverageData);
  }

  private void processCoverageData(ExecutionDataStore coverageData) {
    if (coverageData == null) {
      return;
    }
    IBundleCoverage coverageBundle =
        CoverageUtils.createCoverageBundle(coverageData, outputClassesDirs);
    if (coverageBundle == null) {
      return;
    }

    long coveragePercentage = getCoveragePercentage(coverageBundle);
    span.setTag(Tags.TEST_CODE_COVERAGE_LINES_PERCENTAGE, coveragePercentage);

    File coverageReportFolder = getCoverageReportFolder();
    if (coverageReportFolder != null) {
      CoverageUtils.dumpCoverageReport(
          coverageBundle, repoIndexProvider.getIndex(), repoRoot, coverageReportFolder);
    }
  }

  private static long getCoveragePercentage(IBundleCoverage coverageBundle) {
    ICounter instructionCounter = coverageBundle.getInstructionCounter();
    int totalInstructionsCount = instructionCounter.getTotalCount();
    int coveredInstructionsCount = instructionCounter.getCoveredCount();
    return Math.round((100d * coveredInstructionsCount) / totalInstructionsCount);
  }

  private File getCoverageReportFolder() {
    String coverageReportDumpDir = config.getCiVisibilityCodeCoverageReportDumpDir();
    if (coverageReportDumpDir != null) {
      return Paths.get(coverageReportDumpDir, "session-" + context.getParentId(), moduleName)
          .toFile();
    } else {
      return null;
    }
  }
}
