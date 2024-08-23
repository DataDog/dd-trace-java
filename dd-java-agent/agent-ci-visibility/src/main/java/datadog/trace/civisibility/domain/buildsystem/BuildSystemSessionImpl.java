package datadog.trace.civisibility.domain.buildsystem;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.domain.BuildModuleLayout;
import datadog.trace.api.civisibility.domain.BuildSessionSettings;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.TagValue;
import datadog.trace.api.civisibility.telemetry.tag.EarlyFlakeDetectionAbortReason;
import datadog.trace.api.civisibility.telemetry.tag.Provider;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.InstrumentationType;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.config.ExecutionSettings;
import datadog.trace.civisibility.config.ExecutionSettingsFactory;
import datadog.trace.civisibility.config.JvmInfo;
import datadog.trace.civisibility.coverage.percentage.CoverageCalculator;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.domain.AbstractTestSession;
import datadog.trace.civisibility.domain.BuildSystemSession;
import datadog.trace.civisibility.ipc.ErrorResponse;
import datadog.trace.civisibility.ipc.ExecutionSettingsRequest;
import datadog.trace.civisibility.ipc.ExecutionSettingsResponse;
import datadog.trace.civisibility.ipc.RepoIndexRequest;
import datadog.trace.civisibility.ipc.RepoIndexResponse;
import datadog.trace.civisibility.ipc.SignalResponse;
import datadog.trace.civisibility.ipc.SignalServer;
import datadog.trace.civisibility.ipc.SignalType;
import datadog.trace.civisibility.source.MethodLinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import datadog.trace.civisibility.source.index.RepoIndex;
import datadog.trace.civisibility.source.index.RepoIndexProvider;
import datadog.trace.civisibility.utils.SpanUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
      MethodLinesResolver methodLinesResolver,
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
        methodLinesResolver);
    this.startCommand = startCommand;
    this.moduleSignalRouter = moduleSignalRouter;
    this.executionSettingsFactory = executionSettingsFactory;
    this.signalServer = signalServer;
    this.repoIndexProvider = repoIndexProvider;
    this.coverageCalculatorFactory = coverageCalculatorFactory;
    this.coverageCalculator = coverageCalculatorFactory.sessionCoverage(span.getSpanId());
    this.settings = new BuildSessionSettings(getCoverageEnabledPackages(config, repoIndexProvider));

    signalServer.registerSignalHandler(
        SignalType.MODULE_EXECUTION_RESULT, moduleSignalRouter::onModuleSignalReceived);
    signalServer.registerSignalHandler(
        SignalType.MODULE_COVERAGE_DATA_JACOCO, moduleSignalRouter::onModuleSignalReceived);
    signalServer.registerSignalHandler(
        SignalType.MODULE_COVERAGE_DATA_ITR, moduleSignalRouter::onModuleSignalReceived);
    signalServer.registerSignalHandler(
        SignalType.REPO_INDEX_REQUEST, this::onRepoIndexRequestReceived);
    signalServer.registerSignalHandler(
        SignalType.EXECUTION_SETTINGS_REQUEST, this::onExecutionSettingsRequestReceived);
    signalServer.start();

    setTag(Tags.TEST_COMMAND, startCommand);
  }

  private static List<String> getCoverageEnabledPackages(
      Config config, RepoIndexProvider repoIndexProvider) {
    if (!config.isCiVisibilityCodeCoverageEnabled()) {
      return Collections.emptyList();
    }

    List<String> includedPackages = config.getCiVisibilityCodeCoverageIncludes();
    List<String> packages;
    if (includedPackages != null && !includedPackages.isEmpty()) {
      packages = includedPackages;
    } else {
      RepoIndex repoIndex = repoIndexProvider.getIndex();
      packages = new ArrayList<>(repoIndex.getRootPackages());
    }

    List<String> excludedPackages = config.getCiVisibilityCodeCoverageExcludes();
    if (excludedPackages != null && !excludedPackages.isEmpty()) {
      removeMatchingPackages(packages, excludedPackages);
    }
    return packages;
  }

  private static void removeMatchingPackages(List<String> packages, List<String> excludedPackages) {
    List<String> excludedPrefixes =
        excludedPackages.stream()
            .map(BuildSystemSessionImpl::trimTrailingAsterisk)
            .collect(Collectors.toList());
    Iterator<String> packagesIterator = packages.iterator();
    while (packagesIterator.hasNext()) {
      String p = packagesIterator.next();

      for (String excludedPrefix : excludedPrefixes) {
        if (p.startsWith(excludedPrefix)) {
          packagesIterator.remove();
          break;
        }
      }
    }
  }

  private static String trimTrailingAsterisk(String s) {
    return s.endsWith("*") ? s.substring(0, s.length() - 1) : s;
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
      ExecutionSettings settings = executionSettingsFactory.create(jvmInfo, null);

      String moduleName = request.getModuleName();
      Collection<TestIdentifier> skippableTestsForModule = settings.getSkippableTests(moduleName);
      Map<String, Collection<TestIdentifier>> skippableTests =
          !skippableTestsForModule.isEmpty()
              ? Collections.singletonMap(moduleName, skippableTestsForModule)
              : Collections.emptyMap();

      Collection<TestIdentifier> knownTestsForModule = settings.getKnownTests(moduleName);
      Map<String, Collection<TestIdentifier>> knownTests =
          knownTestsForModule != null
              ? Collections.singletonMap(moduleName, knownTestsForModule)
              : null;

      ExecutionSettings executionSettings =
          new ExecutionSettings(
              settings.isItrEnabled(),
              settings.isCodeCoverageEnabled(),
              settings.isTestSkippingEnabled(),
              settings.isFlakyTestRetriesEnabled(),
              settings.getEarlyFlakeDetectionSettings(),
              settings.getItrCorrelationId(),
              skippableTests,
              settings.getSkippableTestsCoverage(),
              settings.getFlakyTests(moduleName),
              knownTests);

      return new ExecutionSettingsResponse(executionSettings);
    } catch (Exception e) {
      return new ErrorResponse("Error while getting module execution settings: " + e.getMessage());
    }
  }

  @Override
  public BuildSystemModuleImpl testModuleStart(
      String moduleName,
      @Nullable Long startTime,
      BuildModuleLayout moduleLayout,
      JvmInfo jvmInfo) {
    ExecutionSettings executionSettings = executionSettingsFactory.create(jvmInfo, null);
    return new BuildSystemModuleImpl(
        span.context(),
        span.getSpanId(),
        moduleName,
        startCommand,
        startTime,
        signalServer.getAddress(),
        moduleLayout,
        config,
        metricCollector,
        testDecorator,
        sourcePathResolver,
        codeowners,
        methodLinesResolver,
        moduleSignalRouter,
        coverageCalculatorFactory,
        coverageCalculator,
        executionSettings,
        settings,
        this::onModuleFinish);
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
    }

    super.end(endTime);
  }
}
