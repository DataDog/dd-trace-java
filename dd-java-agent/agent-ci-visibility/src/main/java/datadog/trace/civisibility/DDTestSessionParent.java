package datadog.trace.civisibility;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import datadog.trace.api.civisibility.config.SkippableTest;
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
import java.io.File;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashSet;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.data.ExecutionDataStore;

public class DDTestSessionParent extends DDTestSessionImpl {

  private final AgentSpan span;
  private final TestContext context;
  private final String repoRoot;
  private final Config config;
  private final TestModuleRegistry testModuleRegistry;
  private final TestDecorator testDecorator;
  private final SourcePathResolver sourcePathResolver;
  private final Codeowners codeowners;
  private final MethodLinesResolver methodLinesResolver;
  private final CoverageProbeStoreFactory coverageProbeStoreFactory;
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

  public DDTestSessionParent(
      String projectName,
      String repoRoot,
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
    this.repoRoot = repoRoot;
    this.config = config;
    this.testModuleRegistry = testModuleRegistry;
    this.testDecorator = testDecorator;
    this.sourcePathResolver = sourcePathResolver;
    this.codeowners = codeowners;
    this.methodLinesResolver = methodLinesResolver;
    this.moduleExecutionSettingsFactory = moduleExecutionSettingsFactory;
    this.coverageProbeStoreFactory = coverageProbeStoreFactory;
    this.signalServer = signalServer;
    this.repoIndexBuilder = repoIndexBuilder;

    if (startTime != null) {
      span = startSpan(testDecorator.component() + ".test_session", startTime);
    } else {
      span = startSpan(testDecorator.component() + ".test_session");
    }

    context = new SpanTestContext(span, null);

    span.setSpanType(InternalSpanTypes.TEST_SESSION_END);
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_TEST_SESSION);
    span.setTag(Tags.TEST_SESSION_ID, context.getId());

    span.setResourceName(projectName);

    testDecorator.afterStart(span);

    signalServer.registerSignalHandler(
        SignalType.MODULE_EXECUTION_RESULT, this::onModuleExecutionResultReceived);
    signalServer.registerSignalHandler(
        SignalType.REPO_INDEX_REQUEST, this::onRepoIndexRequestReceived);
    signalServer.registerSignalHandler(
        SignalType.SKIPPABLE_TESTS_REQUEST, this::onSkippableTestsRequestReceived);
    signalServer.start();
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
    signalServer.stop();

    String status = context.getStatus();
    span.setTag(Tags.TEST_STATUS, status != null ? status : CIConstants.TEST_SKIP);
    testDecorator.beforeFinish(span);

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

    if (endTime != null) {
      span.finish(endTime);
    } else {
      span.finish();
    }
  }

  private void processCoverageData(ExecutionDataStore coverageData) {
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
      return Paths.get(coverageReportDumpDir, "session-" + context.getId(), "aggregated").toFile();
    } else {
      return null;
    }
  }

  @Override
  public DDTestModuleImpl testModuleStart(
      String moduleName, @Nullable Long startTime, Collection<File> outputClassesDirs) {
    synchronized (coverageDataLock) {
      this.outputClassesDirs.addAll(outputClassesDirs);
    }

    String startCommand = (String) span.getTag(Tags.TEST_COMMAND);
    DDTestModuleParent module =
        new DDTestModuleParent(
            context,
            moduleName,
            repoRoot,
            startCommand,
            startTime,
            outputClassesDirs,
            config,
            testDecorator,
            sourcePathResolver,
            codeowners,
            methodLinesResolver,
            moduleExecutionSettingsFactory,
            repoIndexBuilder,
            coverageProbeStoreFactory,
            signalServer.getAddress());
    testModuleRegistry.addModule(module);
    return module;
  }

  @Override
  public ModuleExecutionSettings getModuleExecutionSettings(JvmInfo jvmInfo) {
    return moduleExecutionSettingsFactory.create(jvmInfo, null);
  }
}
