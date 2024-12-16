package datadog.trace.civisibility.domain;

import static datadog.json.JsonMapper.toJson;
import static datadog.trace.api.civisibility.CIConstants.CI_VISIBILITY_INSTRUMENTATION_NAME;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;

import datadog.trace.api.Config;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.DDTest;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.InstrumentationTestBridge;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.coverage.CoveragePerTestBridge;
import datadog.trace.api.civisibility.coverage.CoverageStore;
import datadog.trace.api.civisibility.domain.TestContext;
import datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.tag.BrowserDriver;
import datadog.trace.api.civisibility.telemetry.tag.EventType;
import datadog.trace.api.civisibility.telemetry.tag.IsNew;
import datadog.trace.api.civisibility.telemetry.tag.IsRetry;
import datadog.trace.api.civisibility.telemetry.tag.IsRum;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.source.LinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import datadog.trace.civisibility.source.SourceResolutionException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestImpl implements DDTest {

  private static final Logger log = LoggerFactory.getLogger(TestImpl.class);

  private final CiVisibilityMetricCollector metricCollector;
  private final TestFrameworkInstrumentation instrumentation;
  private final AgentSpan span;
  private final DDTraceId sessionId;
  private final long suiteId;
  private final Consumer<AgentSpan> onSpanFinish;
  private final TestContext context;

  public TestImpl(
      AgentSpan.Context moduleSpanContext,
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
      Consumer<AgentSpan> onSpanFinish) {
    this.instrumentation = instrumentation;
    this.metricCollector = metricCollector;
    this.sessionId = moduleSpanContext.getTraceId();
    this.suiteId = suiteId;
    this.onSpanFinish = onSpanFinish;

    TestIdentifier identifier = new TestIdentifier(testSuiteName, testName, testParameters);
    CoverageStore coverageStore = coverageStoreFactory.create(identifier);
    CoveragePerTestBridge.setThreadLocalCoverageProbes(coverageStore.getProbes());

    this.context = new TestContextImpl(coverageStore);

    AgentSpan.Context traceContext =
        new TagContext(CIConstants.CIAPP_TEST_ORIGIN, Collections.emptyMap());
    AgentTracer.SpanBuilder spanBuilder =
        AgentTracer.get()
            .buildSpan(CI_VISIBILITY_INSTRUMENTATION_NAME, testDecorator.component() + ".test")
            .ignoreActiveSpan()
            .asChildOf(traceContext)
            .withRequestContextData(RequestContextSlot.CI_VISIBILITY, context);

    if (startTime != null) {
      spanBuilder = spanBuilder.withStartTimestamp(startTime);
    }

    span = spanBuilder.start();

    activateSpan(span, true);

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

      if (skipReason.equals(InstrumentationBridge.ITR_SKIP_REASON)) {
        span.setTag(Tags.TEST_SKIPPED_BY_ITR, true);
        metricCollector.add(CiVisibilityCountMetric.ITR_SKIPPED, 1, EventType.TEST);
      }
    }
  }

  @Override
  public void end(@Nullable Long endTime) {
    closeOutstandingSpans();

    final AgentScope scope = AgentTracer.activeScope();
    if (scope == null) {
      throw new IllegalStateException(
          "No active scope present, it is possible that end() was called multiple times");
    }

    AgentSpan scopeSpan = scope.span();
    if (scopeSpan != span) {
      throw new IllegalStateException(
          "Active scope does not correspond to the finished test, "
              + "it is possible that end() was called multiple times "
              + "or an operation that was started by the test is still in progress; "
              + "active scope span is: "
              + scopeSpan
              + "; "
              + "expected span is: "
              + span);
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

    scope.close();

    onSpanFinish.accept(span);

    if (endTime != null) {
      span.finish(endTime);
    } else {
      span.finish();
    }

    metricCollector.add(
        CiVisibilityCountMetric.EVENT_FINISHED,
        1,
        instrumentation,
        EventType.TEST,
        span.getTag(Tags.TEST_IS_NEW) != null ? IsNew.TRUE : null,
        span.getTag(Tags.TEST_IS_RETRY) != null ? IsRetry.TRUE : null,
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
    AgentScope scope;
    while ((scope = AgentTracer.activeScope()) != null) {
      AgentSpan span = scope.span();

      if (span == this.span || span.getTag(Tags.TEST_SESSION_ID) != null) {
        // encountered this span or another CI Visibility span (test, suite, module, session)
        break;
      }

      log.debug("Closing outstanding span: {}", span);
      scope.close();
      span.finish();
    }
  }
}
