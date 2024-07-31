package datadog.trace.civisibility.domain.buildsystem;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import datadog.trace.api.civisibility.domain.ModuleLayout;
import datadog.trace.api.civisibility.events.BuildEventsHandler;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.InstrumentationType;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.coverage.percentage.CoverageCalculator;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.domain.AbstractTestModule;
import datadog.trace.civisibility.domain.BuildSystemModule;
import datadog.trace.civisibility.ipc.AckResponse;
import datadog.trace.civisibility.ipc.ModuleExecutionResult;
import datadog.trace.civisibility.ipc.SignalResponse;
import datadog.trace.civisibility.ipc.SignalType;
import datadog.trace.civisibility.source.MethodLinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import datadog.trace.civisibility.utils.SpanUtils;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import javax.annotation.Nullable;

public class BuildSystemModuleImpl extends AbstractTestModule implements BuildSystemModule {

  private final InetSocketAddress signalServerAddress;
  private final CoverageCalculator coverageCalculator;
  private final ModuleSignalRouter moduleSignalRouter;

  private final LongAdder testsSkipped = new LongAdder();

  private volatile boolean codeCoverageEnabled;
  private volatile boolean testSkippingEnabled;

  public <T extends CoverageCalculator> BuildSystemModuleImpl(
      AgentSpan.Context sessionSpanContext,
      long sessionId,
      String moduleName,
      String startCommand,
      @Nullable Long startTime,
      InetSocketAddress signalServerAddress,
      ModuleLayout moduleLayout,
      Config config,
      CiVisibilityMetricCollector metricCollector,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver,
      Codeowners codeowners,
      MethodLinesResolver methodLinesResolver,
      ModuleSignalRouter moduleSignalRouter,
      CoverageCalculator.Factory<T> coverageCalculatorFactory,
      T sessionCoverageCalculator,
      ModuleExecutionSettings moduleExecutionSettings,
      Consumer<AgentSpan> onSpanFinish) {
    super(
        sessionSpanContext,
        sessionId,
        moduleName,
        startTime,
        InstrumentationType.BUILD,
        config,
        metricCollector,
        testDecorator,
        sourcePathResolver,
        codeowners,
        methodLinesResolver,
        onSpanFinish);
    this.signalServerAddress = signalServerAddress;
    this.coverageCalculator =
        coverageCalculatorFactory.moduleCoverage(
            span.getSpanId(), moduleLayout, moduleExecutionSettings, sessionCoverageCalculator);
    this.moduleSignalRouter = moduleSignalRouter;

    moduleSignalRouter.registerModuleHandler(
        span.getSpanId(),
        SignalType.MODULE_EXECUTION_RESULT,
        this::onModuleExecutionResultReceived);

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
  private SignalResponse onModuleExecutionResultReceived(ModuleExecutionResult result) {
    if (result.isCoverageEnabled()) {
      codeCoverageEnabled = true;
    }
    if (result.isTestSkippingEnabled()) {
      testSkippingEnabled = true;
    }
    if (result.isEarlyFlakeDetectionEnabled()) {
      setTag(Tags.TEST_EARLY_FLAKE_ENABLED, true);
      if (result.isEarlyFlakeDetectionFaulty()) {
        setTag(Tags.TEST_EARLY_FLAKE_ABORT_REASON, CIConstants.EFD_ABORT_REASON_FAULTY);
      }
    }

    testsSkipped.add(result.getTestsSkippedTotal());

    SpanUtils.mergeTestFrameworks(span, result.getTestFrameworks());

    return AckResponse.INSTANCE;
  }

  @Override
  public void end(@Nullable Long endTime) {
    if (codeCoverageEnabled) {
      setTag(Tags.TEST_CODE_COVERAGE_ENABLED, true);
    }

    if (testSkippingEnabled) {
      setTag(Tags.TEST_ITR_TESTS_SKIPPING_ENABLED, true);
      setTag(Tags.TEST_ITR_TESTS_SKIPPING_TYPE, "test");

      long testsSkippedTotal = testsSkipped.sum();
      setTag(Tags.TEST_ITR_TESTS_SKIPPING_COUNT, testsSkippedTotal);
      if (testsSkippedTotal > 0) {
        setTag(DDTags.CI_ITR_TESTS_SKIPPED, true);
      }
    }

    Long coveragePercentage = coverageCalculator.calculateCoveragePercentage();
    if (coveragePercentage != null) {
      setTag(Tags.TEST_CODE_COVERAGE_LINES_PERCENTAGE, coveragePercentage);
    }

    moduleSignalRouter.removeModuleHandlers(span.getSpanId());

    super.end(endTime);
  }
}
