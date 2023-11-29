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
import datadog.trace.civisibility.utils.SpanUtils;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.data.ExecutionDataStore;

public class DDBuildSystemModuleImpl extends DDTestModuleImpl implements DDBuildSystemModule {

  private final String repoRoot;
  private final InetSocketAddress signalServerAddress;
  private final Collection<File> outputClassesDirs;
  private final RepoIndexProvider repoIndexProvider;
  private final TestModuleRegistry testModuleRegistry;
  private final LongAdder testsSkipped = new LongAdder();
  private volatile boolean codeCoverageEnabled;
  private volatile boolean itrEnabled;
  private final Object coverageDataLock = new Object();

  @GuardedBy("coverageDataLock")
  private ExecutionDataStore coverageData;

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
      TestModuleRegistry testModuleRegistry,
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
    this.testModuleRegistry = testModuleRegistry;

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

  /**
   * Handles module execution results received from a forked JVM.
   *
   * <p>Depending on the build configuration it is possible to have multiple forks created for the
   * same module: in Gradle this is achieved with {@code maxParallelForks} or {@code forkEvery}
   * properties of the Test task, in Maven - with {@code forkCount>} property of the Surefire
   * plugin. The forks can execute either concurrently or sequentially.
   *
   * <p>Taking this into account, the method should merge execution results rather than overwrite
   * them.
   *
   * <p>This method is called by the {@link
   * datadog.trace.util.AgentThreadFactory.AgentThread#CI_SIGNAL_SERVER} thread.
   *
   * @param result Module execution results received from a forked JVM.
   */
  @Override
  public void onModuleExecutionResultReceived(ModuleExecutionResult result) {
    if (result.isCoverageEnabled()) {
      codeCoverageEnabled = true;
    }
    if (result.isItrEnabled()) {
      itrEnabled = true;
    }

    testsSkipped.add(result.getTestsSkippedTotal());

    // it is important that modules parse their own instances of ExecutionDataStore
    // and not share them with the session:
    // ExecutionData instances that reside inside the store are mutable,
    // and modifying an ExecutionData in one module is going
    // to be visible in another module
    // (see internal implementation of org.jacoco.core.data.ExecutionDataStore.accept)
    ExecutionDataStore coverageData = CoverageUtils.parse(result.getCoverageData());
    if (coverageData != null) {
      synchronized (coverageDataLock) {
        if (this.coverageData == null) {
          this.coverageData = coverageData;
        } else {
          // merge module coverage data from multiple VMs
          coverageData.accept(this.coverageData);
        }
      }
    }

    SpanUtils.mergeTestFrameworks(span, result.getTestFrameworks());
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

    synchronized (coverageDataLock) {
      if (coverageData != null && !coverageData.getContents().isEmpty()) {
        processCoverageData(coverageData);
      }
    }

    testModuleRegistry.removeModule(this);

    super.end(endTime);
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
      return Paths.get(coverageReportDumpDir, "session-" + sessionId, moduleName)
          .toAbsolutePath()
          .toFile();
    } else {
      return null;
    }
  }
}
