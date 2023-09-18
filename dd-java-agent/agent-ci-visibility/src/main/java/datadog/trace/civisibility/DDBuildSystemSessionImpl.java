package datadog.trace.civisibility;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import datadog.trace.api.civisibility.config.SkippableTest;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.config.JvmInfo;
import datadog.trace.civisibility.config.ModuleExecutionSettingsFactory;
import datadog.trace.civisibility.coverage.CoverageProbeStoreFactory;
import datadog.trace.civisibility.coverage.CoverageUtils;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.ipc.ErrorResponse;
import datadog.trace.civisibility.ipc.ModuleExecutionResult;
import datadog.trace.civisibility.ipc.RepoIndexRequest;
import datadog.trace.civisibility.ipc.RepoIndexResponse;
import datadog.trace.civisibility.ipc.SignalResponse;
import datadog.trace.civisibility.ipc.SignalServer;
import datadog.trace.civisibility.ipc.SignalType;
import datadog.trace.civisibility.ipc.SkippableTestsRequest;
import datadog.trace.civisibility.ipc.SkippableTestsResponse;
import datadog.trace.civisibility.source.MethodLinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import datadog.trace.civisibility.source.index.RepoIndex;
import datadog.trace.civisibility.source.index.RepoIndexBuilder;
import datadog.trace.civisibility.utils.SpanUtils;
import java.io.File;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.data.ExecutionDataStore;

public class DDBuildSystemSessionImpl extends DDTestSessionImpl implements DDBuildSystemSession {
  private final String repoRoot;
  private final String startCommand;
  private final TestModuleRegistry testModuleRegistry;
  private final ModuleExecutionSettingsFactory moduleExecutionSettingsFactory;
  private final SignalServer signalServer;
  private final RepoIndexBuilder repoIndexBuilder;
  protected final LongAdder testsSkipped = new LongAdder();
  private volatile boolean codeCoverageEnabled;
  private volatile boolean itrEnabled;
  private final Object coverageDataLock = new Object();

  @GuardedBy("coverageDataLock")
  private final ExecutionDataStore coverageData = new ExecutionDataStore();

  @GuardedBy("coverageDataLock")
  private final Collection<File> outputClassesDirs = new HashSet<>();

  public DDBuildSystemSessionImpl(
      String projectName,
      String repoRoot,
      String startCommand,
      @Nullable Long startTime,
      Config config,
      TestModuleRegistry testModuleRegistry,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver,
      Codeowners codeowners,
      MethodLinesResolver methodLinesResolver,
      ModuleExecutionSettingsFactory moduleExecutionSettingsFactory,
      CoverageProbeStoreFactory coverageProbeStoreFactory,
      SignalServer signalServer,
      RepoIndexBuilder repoIndexBuilder) {
    super(
        projectName,
        startTime,
        config,
        testDecorator,
        sourcePathResolver,
        codeowners,
        methodLinesResolver,
        coverageProbeStoreFactory);
    this.repoRoot = repoRoot;
    this.startCommand = startCommand;
    this.testModuleRegistry = testModuleRegistry;
    this.moduleExecutionSettingsFactory = moduleExecutionSettingsFactory;
    this.signalServer = signalServer;
    this.repoIndexBuilder = repoIndexBuilder;

    signalServer.registerSignalHandler(
        SignalType.MODULE_EXECUTION_RESULT, this::onModuleExecutionResultReceived);
    signalServer.registerSignalHandler(
        SignalType.REPO_INDEX_REQUEST, this::onRepoIndexRequestReceived);
    signalServer.registerSignalHandler(
        SignalType.SKIPPABLE_TESTS_REQUEST, this::onSkippableTestsRequestReceived);
    signalServer.start();

    setTag(Tags.TEST_COMMAND, startCommand);
  }

  private SignalResponse onModuleExecutionResultReceived(ModuleExecutionResult result) {
    if (result.isCoverageEnabled()) {
      codeCoverageEnabled = true;
    }
    if (result.isItrEnabled()) {
      itrEnabled = true;
    }
    String testFramework = result.getTestFramework();
    if (testFramework != null) {
      setTag(Tags.TEST_FRAMEWORK, testFramework);
    }
    String testFrameworkVersion = result.getTestFrameworkVersion();
    if (testFrameworkVersion != null) {
      setTag(Tags.TEST_FRAMEWORK_VERSION, testFrameworkVersion);
    }

    testsSkipped.add(result.getTestsSkippedTotal());

    ExecutionDataStore moduleCoverageData = CoverageUtils.parse(result.getCoverageData());
    if (moduleCoverageData != null) {
      synchronized (coverageDataLock) {
        // add module coverage data to session coverage data
        moduleCoverageData.accept(coverageData);
      }
    }

    return testModuleRegistry.onModuleExecutionResultReceived(result, moduleCoverageData);
  }

  private SignalResponse onRepoIndexRequestReceived(RepoIndexRequest request) {
    try {
      RepoIndex index = repoIndexBuilder.getIndex();
      return new RepoIndexResponse(index);
    } catch (Exception e) {
      return new ErrorResponse("Error while building repo index: " + e.getMessage());
    }
  }

  private SignalResponse onSkippableTestsRequestReceived(
      SkippableTestsRequest skippableTestsRequest) {
    try {
      String relativeModulePath = skippableTestsRequest.getRelativeModulePath();
      JvmInfo jvmInfo = skippableTestsRequest.getJvmInfo();
      ModuleExecutionSettings moduleExecutionSettings = getModuleExecutionSettings(jvmInfo);
      Collection<SkippableTest> tests =
          moduleExecutionSettings.getSkippableTests(relativeModulePath);
      return new SkippableTestsResponse(tests);
    } catch (Exception e) {
      return new ErrorResponse("Error while getting skippable tests: " + e.getMessage());
    }
  }

  @Override
  public void end(@Nullable Long endTime) {
    signalServer.stop();

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
      if (!coverageData.getContents().isEmpty()) {
        processCoverageData(coverageData);
      }
    }

    super.end(endTime);
  }

  private void processCoverageData(ExecutionDataStore coverageData) {
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
          coverageBundle, repoIndexBuilder.getIndex(), repoRoot, coverageReportFolder);
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
      return Paths.get(coverageReportDumpDir, "session-" + span.getSpanId(), "aggregated").toFile();
    } else {
      return null;
    }
  }

  @Override
  public DDBuildSystemModuleImpl testModuleStart(String moduleName, @Nullable Long startTime) {
    return testModuleStart(moduleName, startTime, Collections.emptySet());
  }

  @Override
  public DDBuildSystemModuleImpl testModuleStart(
      String moduleName, @Nullable Long startTime, Collection<File> outputClassesDirs) {
    synchronized (coverageDataLock) {
      this.outputClassesDirs.addAll(outputClassesDirs);
    }

    DDBuildSystemModuleImpl module =
        new DDBuildSystemModuleImpl(
            span.context(),
            span.getSpanId(),
            moduleName,
            repoRoot,
            startCommand,
            startTime,
            signalServer.getAddress(),
            outputClassesDirs,
            config,
            testDecorator,
            sourcePathResolver,
            codeowners,
            methodLinesResolver,
            coverageProbeStoreFactory,
            repoIndexBuilder,
            SpanUtils.propagateCiVisibilityTagsTo(span));
    testModuleRegistry.addModule(module);
    return module;
  }

  @Override
  public ModuleExecutionSettings getModuleExecutionSettings(JvmInfo jvmInfo) {
    return moduleExecutionSettingsFactory.create(jvmInfo, null);
  }
}
