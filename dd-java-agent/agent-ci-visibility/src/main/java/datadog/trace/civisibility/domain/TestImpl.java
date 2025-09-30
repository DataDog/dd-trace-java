package datadog.trace.civisibility.domain;

import static datadog.json.JsonMapper.toJson;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpanWithoutScope;
import static datadog.trace.civisibility.Constants.CI_VISIBILITY_INSTRUMENTATION_NAME;

import datadog.trace.api.Config;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.DDTest;
import datadog.trace.api.civisibility.InstrumentationTestBridge;
import datadog.trace.api.civisibility.config.LibraryCapability;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.coverage.CoveragePerTestBridge;
import datadog.trace.api.civisibility.coverage.CoverageStore;
import datadog.trace.api.civisibility.domain.TestContext;
import datadog.trace.api.civisibility.execution.TestStatus;
import datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.TagValue;
import datadog.trace.api.civisibility.telemetry.tag.BrowserDriver;
import datadog.trace.api.civisibility.telemetry.tag.EventType;
import datadog.trace.api.civisibility.telemetry.tag.FailedTestReplayEnabled;
import datadog.trace.api.civisibility.telemetry.tag.HasFailedAllRetries;
import datadog.trace.api.civisibility.telemetry.tag.IsAttemptToFix;
import datadog.trace.api.civisibility.telemetry.tag.IsDisabled;
import datadog.trace.api.civisibility.telemetry.tag.IsModified;
import datadog.trace.api.civisibility.telemetry.tag.IsNew;
import datadog.trace.api.civisibility.telemetry.tag.IsQuarantined;
import datadog.trace.api.civisibility.telemetry.tag.IsRetry;
import datadog.trace.api.civisibility.telemetry.tag.IsRum;
import datadog.trace.api.civisibility.telemetry.tag.SkipReason;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.source.LinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import datadog.trace.civisibility.source.SourceResolutionException;
import datadog.trace.civisibility.test.ExecutionResults;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestImpl implements DDTest {

  private static final Logger log = LoggerFactory.getLogger(TestImpl.class);

  private final CiVisibilityMetricCollector metricCollector;
  private final ExecutionResults executionResults;
  private final TestFrameworkInstrumentation instrumentation;
  private final AgentSpan span;
  private final DDTraceId sessionId;
  private final long suiteId;
  private final Consumer<AgentSpan> onSpanFinish;
  private final TestContext context;
  private final TestIdentifier identifier;
  private final long startMicros;

  public TestImpl(
      AgentSpanContext moduleSpanContext,
      long suiteId,
      String moduleName,
      String testSuiteName,
      String testName,
      @Nullable String testParameters,
      @Nullable String itrCorrelationId,
      @Nullable Long startTime,
      @Nullable Class<?> testClass,
      @Nullable Method testMethod,
      InstrumentationType instrumentationType,
      TestFrameworkInstrumentation instrumentation,
      Config config,
      CiVisibilityMetricCollector metricCollector,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver,
      LinesResolver linesResolver,
      Codeowners codeowners,
      CoverageStore.Factory coverageStoreFactory,
      ExecutionResults executionResults,
      @Nonnull Collection<LibraryCapability> capabilities,
      Consumer<AgentSpan> onSpanFinish) {
    this.instrumentation = instrumentation;
    this.metricCollector = metricCollector;
    this.sessionId = moduleSpanContext.getTraceId();
    this.suiteId = suiteId;
    this.executionResults = executionResults;
    this.onSpanFinish = onSpanFinish;

    this.identifier = new TestIdentifier(testSuiteName, testName, testParameters);
    CoverageStore coverageStore = coverageStoreFactory.create(identifier);
    CoveragePerTestBridge.setThreadLocalCoverageProbes(coverageStore.getProbes());

    this.context = new TestContextImpl(coverageStore);

    AgentSpanContext traceContext = new TagContext(CIConstants.CIAPP_TEST_ORIGIN, null);
    AgentTracer.SpanBuilder spanBuilder =
        AgentTracer.get()
            .buildSpan(CI_VISIBILITY_INSTRUMENTATION_NAME, testDecorator.component() + ".test")
            .ignoreActiveSpan()
            .asChildOf(traceContext)
            .withRequestContextData(RequestContextSlot.CI_VISIBILITY, context);

    if (startTime != null) {
      startMicros = startTime;
      spanBuilder = spanBuilder.withStartTimestamp(startTime);
    } else {
      startMicros = SystemTimeSource.INSTANCE.getCurrentTimeMicros();
    }

    span = spanBuilder.start();

    activateSpanWithoutScope(span);

    span.setSpanType(InternalSpanTypes.TEST);
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_TEST);

    span.setResourceName(testSuiteName + "." + testName);
    span.setTag(Tags.TEST_NAME, testName);
    span.setTag(Tags.TEST_SUITE, testSuiteName);
    span.setTag(Tags.TEST_MODULE, moduleName);

    span.setTag(Tags.TEST_SUITE_ID, suiteId);
    span.setTag(Tags.TEST_MODULE_ID, moduleSpanContext.getSpanId());
    span.setTag(Tags.TEST_SESSION_ID, moduleSpanContext.getTraceId());

    span.setTag(Tags.TEST_STATUS, TestStatus.pass);

    if (testClass != null && !testClass.getName().equals(testSuiteName)) {
      span.setTag(Tags.TEST_SOURCE_CLASS, testClass.getName());
    }

    if (config.isCiVisibilitySourceDataEnabled()) {
      populateSourceDataTags(
          span, testClass, testMethod, sourcePathResolver, linesResolver, codeowners);
    }

    if (itrCorrelationId != null) {
      span.setTag(Tags.ITR_CORRELATION_ID, itrCorrelationId);
    }

    for (LibraryCapability capability : capabilities) {
      span.setTag(capability.asTag(), capability.getVersion());
    }

    testDecorator.afterStart(span);

    metricCollector.add(CiVisibilityCountMetric.EVENT_CREATED, 1, instrumentation, EventType.TEST);

    if (instrumentationType == InstrumentationType.MANUAL_API) {
      metricCollector.add(CiVisibilityCountMetric.MANUAL_API_EVENTS, 1, EventType.TEST);
    }
  }

  private void populateSourceDataTags(
      AgentSpan span,
      Class<?> testClass,
      Method testMethod,
      SourcePathResolver sourcePathResolver,
      LinesResolver linesResolver,
      Codeowners codeowners) {
    if (testClass == null) {
      return;
    }

    String sourcePath;
    try {
      sourcePath = sourcePathResolver.getSourcePath(testClass);
      if (sourcePath == null || sourcePath.isEmpty()) {
        return;
      }
    } catch (SourceResolutionException e) {
      log.debug("Could not populate source path for {}", testClass, e);
      return;
    }

    span.setTag(Tags.TEST_SOURCE_FILE, sourcePath);

    if (testMethod != null) {
      LinesResolver.Lines testMethodLines = linesResolver.getMethodLines(testMethod);
      if (testMethodLines.isValid()) {
        span.setTag(Tags.TEST_SOURCE_START, testMethodLines.getStartLineNumber());
        span.setTag(Tags.TEST_SOURCE_END, testMethodLines.getEndLineNumber());
      }
    }

    Collection<String> testCodeOwners = codeowners.getOwners(sourcePath);
    if (testCodeOwners != null) {
      span.setTag(Tags.TEST_CODEOWNERS, toJson(testCodeOwners));
    }
  }

  @Nonnull
  public TestIdentifier getIdentifier() {
    return identifier;
  }

  @Override
  public void setTag(String key, Object value) {
    span.setTag(key, value);
  }

  @Override
  public void setErrorInfo(Throwable error) {
    span.setError(true);
    span.addThrowable(error);
    span.setTag(Tags.TEST_STATUS, TestStatus.fail);
  }

  @Override
  public void setSkipReason(String skipReason) {
    span.setTag(Tags.TEST_STATUS, TestStatus.skip);
    if (skipReason != null) {
      span.setTag(Tags.TEST_SKIP_REASON, skipReason);

      if (skipReason.equals(SkipReason.ITR.getDescription())) {
        span.setTag(Tags.TEST_SKIPPED_BY_ITR, true);
        metricCollector.add(CiVisibilityCountMetric.ITR_SKIPPED, 1, EventType.TEST);
        executionResults.incrementTestsSkippedByItr();
      }
    }
  }

  public TestStatus getStatus() {
    return (TestStatus) span.getTag(Tags.TEST_STATUS);
  }

  public long getDuration(@Nullable Long endMicros) {
    if (endMicros == null) {
      endMicros = SystemTimeSource.INSTANCE.getCurrentTimeMicros();
    }
    return TimeUnit.MICROSECONDS.toMillis(endMicros - startMicros);
  }

  public TestContext getContext() {
    return context;
  }

  @Override
  public void end(@Nullable Long endTime) {
    closeOutstandingSpans();

    final AgentSpan activeSpan = AgentTracer.activeSpan();
    if (activeSpan == null) {
      throw new IllegalStateException(
          "No active span present, it is possible that end() was called multiple times");
    }

    if (activeSpan != this.span) {
      throw new IllegalStateException(
          "Active span does not correspond to the finished test, "
              + "it is possible that end() was called multiple times "
              + "or an operation that was started by the test is still in progress; "
              + "active span is: "
              + activeSpan
              + "; "
              + "expected span is: "
              + this.span);
    }

    InstrumentationTestBridge.fireBeforeTestEnd(context);

    CoveragePerTestBridge.removeThreadLocalCoverageProbes();

    // do not process coverage reports for skipped tests
    if (span.getTag(Tags.TEST_STATUS) != TestStatus.skip) {
      CoverageStore coverageStore = context.getCoverageStore();
      boolean coveragesGathered = coverageStore.report(sessionId, suiteId, span.getSpanId());
      if (!coveragesGathered && !TestStatus.skip.equals(span.getTag(Tags.TEST_STATUS))) {
        // test is not skipped, but no coverages were gathered
        metricCollector.add(CiVisibilityCountMetric.CODE_COVERAGE_IS_EMPTY, 1);
      }
    }

    boolean debugInfoCaptured = span.getTag(Tags.ERROR_DEBUG_INFO_CAPTURED) != null;
    if (debugInfoCaptured) {
      executionResults.setHasFailedTestReplayTests();
    }

    AgentTracer.closeActive();

    onSpanFinish.accept(span);

    if (endTime != null) {
      span.finish(endTime);
    } else {
      span.finish();
    }

    Object retryReason = span.getTag(Tags.TEST_RETRY_REASON);
    metricCollector.add(
        CiVisibilityCountMetric.TEST_EVENT_FINISHED,
        1,
        instrumentation,
        EventType.TEST,
        span.getTag(Tags.TEST_IS_NEW) != null ? IsNew.TRUE : null,
        span.getTag(Tags.TEST_IS_MODIFIED) != null ? IsModified.TRUE : null,
        span.getTag(Tags.TEST_TEST_MANAGEMENT_IS_QUARANTINED) != null ? IsQuarantined.TRUE : null,
        span.getTag(Tags.TEST_TEST_MANAGEMENT_IS_TEST_DISABLED) != null ? IsDisabled.TRUE : null,
        span.getTag(Tags.TEST_TEST_MANAGEMENT_IS_ATTEMPT_TO_FIX) != null
            ? IsAttemptToFix.TRUE
            : null,
        span.getTag(Tags.TEST_IS_RETRY) != null ? IsRetry.TRUE : null,
        span.getTag(Tags.TEST_HAS_FAILED_ALL_RETRIES) != null ? HasFailedAllRetries.TRUE : null,
        retryReason instanceof TagValue ? (TagValue) retryReason : null,
        debugInfoCaptured ? FailedTestReplayEnabled.TestMetric.TRUE : null,
        span.getTag(Tags.TEST_IS_RUM_ACTIVE) != null ? IsRum.TRUE : null,
        CIConstants.SELENIUM_BROWSER_DRIVER.equals(span.getTag(Tags.TEST_BROWSER_DRIVER))
            ? BrowserDriver.SELENIUM
            : null);
  }

  /**
   * Tests often perform operations that involve APM instrumentations: sending an HTTP request,
   * executing a database query, etc. APM instrumentations create spans that correspond to those
   * operations. Ideally, the instrumentations close these spans once their corresponding operations
   * are finished.
   *
   * <p>However, this is not always the case, especially with tests: developers sometimes feel like
   * proper resources disposal, such as closing a connection, is not obligatory in tests code. Not
   * finalizing an operation properly usually results in its span remaining open. This is something
   * that we have no control over, since this happens in the clients' codebase.
   *
   * <p>This method attempts to finalize such "dangling" spans: it closes whatever is on top of the
   * spans stack until it encounters a CI Visibility span or the stack is empty.
   */
  private void closeOutstandingSpans() {
    AgentSpan activeSpan;
    while ((activeSpan = AgentTracer.activeSpan()) != null) {

      if (activeSpan == this.span || activeSpan.getTag(Tags.TEST_SESSION_ID) != null) {
        // encountered this span or another CI Visibility span (test, suite, module, session)
        break;
      }

      log.debug("Closing outstanding span: {}", activeSpan);
      AgentTracer.closeActive();
      activeSpan.finish();
    }
  }
}
