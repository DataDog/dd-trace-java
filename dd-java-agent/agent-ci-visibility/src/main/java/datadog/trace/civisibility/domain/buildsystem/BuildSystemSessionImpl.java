package datadog.trace.civisibility.domain.buildsystem;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.domain.BuildModuleLayout;
import datadog.trace.api.civisibility.domain.BuildSessionSettings;
import datadog.trace.api.civisibility.domain.JavaAgent;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.TagValue;
import datadog.trace.api.civisibility.telemetry.tag.EarlyFlakeDetectionAbortReason;
import datadog.trace.api.civisibility.telemetry.tag.Provider;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.config.ExecutionSettings;
import datadog.trace.civisibility.config.ExecutionSettingsFactory;
import datadog.trace.civisibility.config.JvmInfo;
import datadog.trace.civisibility.coverage.percentage.CoverageCalculator;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.domain.AbstractTestSession;
import datadog.trace.civisibility.domain.BuildSystemSession;
import datadog.trace.civisibility.domain.InstrumentationType;
import datadog.trace.civisibility.ipc.ErrorResponse;
import datadog.trace.civisibility.ipc.ExecutionSettingsRequest;
import datadog.trace.civisibility.ipc.ExecutionSettingsResponse;
import datadog.trace.civisibility.ipc.RepoIndexRequest;
import datadog.trace.civisibility.ipc.RepoIndexResponse;
import datadog.trace.civisibility.ipc.SignalResponse;
import datadog.trace.civisibility.ipc.SignalServer;
import datadog.trace.civisibility.ipc.SignalType;
import datadog.trace.civisibility.source.LinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import datadog.trace.civisibility.source.index.RepoIndex;
import datadog.trace.civisibility.source.index.RepoIndexProvider;
import datadog.trace.civisibility.utils.SpanUtils;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

public class BuildSystemSessionImpl<T extends CoverageCalculator> extends AbstractTestSession
    implements BuildSystemSession {

  private final String startCommand;
  private final ModuleSignalRouter moduleSignalRouter;
  private final ExecutionSettingsFactory executionSettingsFactory;
  private final SignalServer signalServer;
  private final RepoIndexProvider repoIndexProvider;
  private final CoverageCalculator.Factory<T> coverageCalculatorFactory;
  private final T coverageCalculator;
  private final BuildSessionSettings settings;
  private final Object tagPropagationLock = new Object();

  public BuildSystemSessionImpl(
      String projectName,
      String startCommand,
      @Nullable Long startTime,
      Provider ciProvider,
      Config config,
      CiVisibilityMetricCollector metricCollector,
      ModuleSignalRouter moduleSignalRouter,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver,
      Codeowners codeowners,
      LinesResolver linesResolver,
      ExecutionSettingsFactory executionSettingsFactory,
      SignalServer signalServer,
      RepoIndexProvider repoIndexProvider,
      CoverageCalculator.Factory<T> coverageCalculatorFactory) {
    super(
        projectName,
        startTime,
        InstrumentationType.BUILD,
        ciProvider,
        config,
        metricCollector,
        testDecorator,
        sourcePathResolver,
        codeowners,
        linesResolver);
    this.startCommand = startCommand;
    this.moduleSignalRouter = moduleSignalRouter;
    this.executionSettingsFactory = executionSettingsFactory;
    this.signalServer = signalServer;
    this.repoIndexProvider = repoIndexProvider;
    this.coverageCalculatorFactory = coverageCalculatorFactory;
    this.coverageCalculator = coverageCalculatorFactory.sessionCoverage(span.getSpanId());
    this.settings =
        new BuildSessionSettings(
            getCoverageIncludedPackages(config, repoIndexProvider),
            config.getCiVisibilityCodeCoverageExcludes());

    signalServer.registerSignalHandler(
        SignalType.MODULE_EXECUTION_RESULT, moduleSignalRouter::onModuleSignalReceived);
    signalServer.registerSignalHandler(
        SignalType.MODULE_COVERAGE_DATA_JACOCO, moduleSignalRouter::onModuleSignalReceived);
    signalServer.registerSignalHandler(
        SignalType.REPO_INDEX_REQUEST, this::onRepoIndexRequestReceived);
    signalServer.registerSignalHandler(
        SignalType.EXECUTION_SETTINGS_REQUEST, this::onExecutionSettingsRequestReceived);
    signalServer.start();

    setTag(Tags.TEST_COMMAND, startCommand);
  }

  private static List<String> getCoverageIncludedPackages(
      Config config, RepoIndexProvider repoIndexProvider) {
    if (!config.isCiVisibilityCodeCoverageEnabled()) {
      return Collections.emptyList();
    }

    List<String> includedPackages = config.getCiVisibilityCodeCoverageIncludes();
    if (includedPackages != null && !includedPackages.isEmpty()) {
      return new ArrayList<>(includedPackages);
    } else {
      RepoIndex repoIndex = repoIndexProvider.getIndex();
      return new ArrayList<>(repoIndex.getRootPackages());
    }
  }

  private SignalResponse onRepoIndexRequestReceived(RepoIndexRequest request) {
    try {
      RepoIndex index = repoIndexProvider.getIndex();
      return new RepoIndexResponse(index);
    } catch (Exception e) {
      return new ErrorResponse("Error while building repo index: " + e.getMessage());
    }
  }

  private SignalResponse onExecutionSettingsRequestReceived(ExecutionSettingsRequest request) {
    try {
      JvmInfo jvmInfo = request.getJvmInfo();
      String moduleName = request.getModuleName();
      ExecutionSettings settings = executionSettingsFactory.create(jvmInfo, moduleName);
      return new ExecutionSettingsResponse(settings);

    } catch (Exception e) {
      return new ErrorResponse("Error while getting module execution settings: " + e.getMessage());
    }
  }

  @Override
  public BuildSystemModuleImpl testModuleStart(
      String moduleName,
      @Nullable Long startTime,
      BuildModuleLayout moduleLayout,
      JvmInfo jvmInfo,
      @Nullable Collection<Path> classpath,
      @Nullable JavaAgent jacocoAgent) {
    ExecutionSettings executionSettings = executionSettingsFactory.create(jvmInfo, moduleName);
    return new BuildSystemModuleImpl(
        span.context(),
        moduleName,
        startCommand,
        startTime,
        signalServer.getAddress(),
        moduleLayout,
        classpath,
        jacocoAgent,
        config,
        metricCollector,
        testDecorator,
        sourcePathResolver,
        codeowners,
        linesResolver,
        moduleSignalRouter,
        coverageCalculatorFactory,
        coverageCalculator,
        executionSettings,
        settings,
        this::onModuleFinish);
  }

  @Override
  public AgentSpan testTaskStart(String taskName) {
    return startSpan("ci_visibility", taskName, span.context());
  }

  private void onModuleFinish(AgentSpan moduleSpan) {
    // multiple modules can finish in parallel
    synchronized (tagPropagationLock) {
      SpanUtils.propagateCiVisibilityTags(span, moduleSpan);

      SpanUtils.propagateTag(span, moduleSpan, Tags.TEST_EARLY_FLAKE_ENABLED, Boolean::logicalOr);
      SpanUtils.propagateTag(span, moduleSpan, Tags.TEST_EARLY_FLAKE_ABORT_REASON);

      SpanUtils.propagateTag(span, moduleSpan, Tags.TEST_CODE_COVERAGE_ENABLED, Boolean::logicalOr);
      SpanUtils.propagateTag(
          span, moduleSpan, Tags.TEST_ITR_TESTS_SKIPPING_ENABLED, Boolean::logicalOr);
      SpanUtils.propagateTag(span, moduleSpan, Tags.TEST_ITR_TESTS_SKIPPING_TYPE);
      SpanUtils.propagateTag(span, moduleSpan, Tags.TEST_ITR_TESTS_SKIPPING_COUNT, Long::sum);
      SpanUtils.propagateTag(span, moduleSpan, DDTags.CI_ITR_TESTS_SKIPPED, Boolean::logicalOr);
    }
  }

  @Override
  public BuildSessionSettings getSettings() {
    return settings;
  }

  @Override
  protected Collection<TagValue> additionalTelemetryTags() {
    if (CIConstants.EFD_ABORT_REASON_FAULTY.equals(
        span.getTag(Tags.TEST_EARLY_FLAKE_ABORT_REASON))) {
      return Collections.singleton(EarlyFlakeDetectionAbortReason.FAULTY);
    }
    return Collections.emptySet();
  }

  @Override
  public void end(@Nullable Long endTime) {
    signalServer.stop();

    Long coveragePercentage = coverageCalculator.calculateCoveragePercentage();
    if (coveragePercentage != null) {
      setTag(Tags.TEST_CODE_COVERAGE_LINES_PERCENTAGE, coveragePercentage);

      Object testSkippingEnabled = span.getTag(Tags.TEST_ITR_TESTS_SKIPPING_ENABLED);
      if (testSkippingEnabled != null && (Boolean) testSkippingEnabled) {
        setTag(Tags.TEST_CODE_COVERAGE_BACKFILLED, true);
      }
    }

    super.end(endTime);
  }
}
