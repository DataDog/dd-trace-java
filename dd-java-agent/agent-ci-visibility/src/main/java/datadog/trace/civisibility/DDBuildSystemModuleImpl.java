package datadog.trace.civisibility;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.civisibility.events.BuildEventsHandler;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.codeowners.Codeowners;
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
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.data.ExecutionDataStore;

public class DDBuildSystemModuleImpl extends DDTestModuleImpl implements DDBuildSystemModule {

  private final String repoRoot;
  private final InetSocketAddress signalServerAddress;
  private final Collection<File> outputClassesDirs;
  private final RepoIndexProvider repoIndexProvider;
  private final LongAdder testsSkipped = new LongAdder();
  private volatile boolean codeCoverageEnabled;
  private volatile boolean itrEnabled;

  public DDBuildSystemModuleImpl(
      AgentSpan.Context sessionSpanContext,
      long sessionId,
      String moduleName,
      String repoRoot,
      String startCommand,
      @Nullable Long startTime,
      InetSocketAddress signalServerAddress,
      Collection<File> outputClassesDirs,
      Config config,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver,
      Codeowners codeowners,
      MethodLinesResolver methodLinesResolver,
      CoverageProbeStoreFactory coverageProbeStoreFactory,
      RepoIndexProvider repoIndexProvider,
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
    this.repoRoot = repoRoot;
    this.signalServerAddress = signalServerAddress;
    this.outputClassesDirs = outputClassesDirs;
    this.repoIndexProvider = repoIndexProvider;

    setTag(Tags.TEST_COMMAND, startCommand);
  }

  @Override
  public long getId() {
    return span.getSpanId();
  }

  @Override
  public BuildEventsHandler.ModuleInfo getModuleInfo() {
    long moduleId = span.getSpanId();
    String signalServerHost =
        signalServerAddress != null ? signalServerAddress.getHostName() : null;
    int signalServerPort = signalServerAddress != null ? signalServerAddress.getPort() : 0;
    return new BuildEventsHandler.ModuleInfo(
        moduleId, sessionId, signalServerHost, signalServerPort);
  }

  @Override
  public void onModuleExecutionResultReceived(
      ModuleExecutionResult result, ExecutionDataStore coverageData) {
    codeCoverageEnabled = result.isCoverageEnabled();
    itrEnabled = result.isItrEnabled();
    testsSkipped.add(result.getTestsSkippedTotal());

    String testFramework = result.getTestFramework();
    if (testFramework != null) {
      setTag(Tags.TEST_FRAMEWORK, testFramework);
    }

    String testFrameworkVersion = result.getTestFrameworkVersion();
    if (testFrameworkVersion != null) {
      setTag(Tags.TEST_FRAMEWORK_VERSION, testFrameworkVersion);
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
    setTag(Tags.TEST_CODE_COVERAGE_LINES_PERCENTAGE, coveragePercentage);

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
      return Paths.get(coverageReportDumpDir, "session-" + sessionId, moduleName).toFile();
    } else {
      return null;
    }
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
