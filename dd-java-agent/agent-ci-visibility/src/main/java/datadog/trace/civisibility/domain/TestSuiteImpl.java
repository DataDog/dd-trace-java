package datadog.trace.civisibility.domain;

import static datadog.json.JsonMapper.toJson;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpanWithoutScope;
import static datadog.trace.civisibility.Constants.CI_VISIBILITY_INSTRUMENTATION_NAME;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.DDTestSuite;
import datadog.trace.api.civisibility.config.LibraryCapability;
import datadog.trace.api.civisibility.coverage.CoverageStore;
import datadog.trace.api.civisibility.execution.TestStatus;
import datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.tag.EventType;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.source.LinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import datadog.trace.civisibility.source.SourceResolutionException;
import datadog.trace.civisibility.test.ExecutionResults;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestSuiteImpl implements DDTestSuite {

  private static final Logger log = LoggerFactory.getLogger(TestSuiteImpl.class);

  private final AgentSpanContext moduleSpanContext;
  private final AgentSpan span;
  private final String moduleName;
  private final String testSuiteName;
  private final String itrCorrelationId;
  private final Class<?> testClass;
  private final InstrumentationType instrumentationType;
  private final TestFrameworkInstrumentation instrumentation;
  private final Config config;
  private final CiVisibilityMetricCollector metricCollector;
  private final TestDecorator testDecorator;
  private final SourcePathResolver sourcePathResolver;
  private final Codeowners codeowners;
  private final LinesResolver linesResolver;
  private final CoverageStore.Factory coverageStoreFactory;
  private final ExecutionResults executionResults;
  private final boolean parallelized;
  private final Collection<LibraryCapability> capabilities;
  private final Consumer<AgentSpan> onSpanFinish;
  private final SpanTagsPropagator tagsPropagator;

  public TestSuiteImpl(
      AgentSpanContext moduleSpanContext,
      String moduleName,
      String testSuiteName,
      String itrCorrelationId,
      @Nullable Class<?> testClass,
      @Nullable Long startTime,
      boolean parallelized,
      InstrumentationType instrumentationType,
      TestFrameworkInstrumentation instrumentation,
      Config config,
      CiVisibilityMetricCollector metricCollector,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver,
      Codeowners codeowners,
      LinesResolver linesResolver,
      CoverageStore.Factory coverageStoreFactory,
      ExecutionResults executionResults,
      @Nonnull Collection<LibraryCapability> capabilities,
      Consumer<AgentSpan> onSpanFinish) {
    this.moduleSpanContext = moduleSpanContext;
    this.moduleName = moduleName;
    this.testSuiteName = testSuiteName;
    this.itrCorrelationId = itrCorrelationId;
    this.parallelized = parallelized;
    this.instrumentationType = instrumentationType;
    this.instrumentation = instrumentation;
    this.config = config;
    this.metricCollector = metricCollector;
    this.testDecorator = testDecorator;
    this.sourcePathResolver = sourcePathResolver;
    this.codeowners = codeowners;
    this.linesResolver = linesResolver;
    this.coverageStoreFactory = coverageStoreFactory;
    this.executionResults = executionResults;
    this.capabilities = capabilities;
    this.onSpanFinish = onSpanFinish;

    AgentTracer.SpanBuilder spanBuilder =
        AgentTracer.get()
            .buildSpan(
                CI_VISIBILITY_INSTRUMENTATION_NAME, testDecorator.component() + ".test_suite")
            .asChildOf(moduleSpanContext);

    if (startTime != null) {
      spanBuilder = spanBuilder.withStartTimestamp(startTime);
    }

    span = spanBuilder.start();
    tagsPropagator = new SpanTagsPropagator(span);

    span.setSpanType(InternalSpanTypes.TEST_SUITE_END);
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_TEST_SUITE);

    span.setResourceName(testSuiteName);
    span.setTag(Tags.TEST_SUITE, testSuiteName);
    span.setTag(Tags.TEST_MODULE, moduleName);

    span.setTag(Tags.TEST_SUITE_ID, span.getSpanId());
    span.setTag(Tags.TEST_MODULE_ID, moduleSpanContext.getSpanId());
    span.setTag(Tags.TEST_SESSION_ID, moduleSpanContext.getTraceId());

    // setting status to skip initially,
    // as we do not know in advance whether the suite will have any children
    span.setTag(Tags.TEST_STATUS, TestStatus.skip);

    this.testClass = testClass;

    if (config.isCiVisibilitySourceDataEnabled()) {
      populateSourceDataTags(span, testClass, sourcePathResolver, codeowners);
    }

    testDecorator.afterStart(span);

    if (!parallelized) {
      activateSpanWithoutScope(span);
    }

    metricCollector.add(CiVisibilityCountMetric.EVENT_CREATED, 1, instrumentation, EventType.SUITE);

    if (instrumentationType == InstrumentationType.MANUAL_API) {
      metricCollector.add(CiVisibilityCountMetric.MANUAL_API_EVENTS, 1, EventType.SUITE);
    }
  }

  private void populateSourceDataTags(
      AgentSpan span,
      Class<?> testClass,
      SourcePathResolver sourcePathResolver,
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

    LinesResolver.Lines testClassLines = linesResolver.getClassLines(testClass);
    if (testClassLines.isValid()) {
      span.setTag(Tags.TEST_SOURCE_START, testClassLines.getStartLineNumber());
      span.setTag(Tags.TEST_SOURCE_END, testClassLines.getEndLineNumber());
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
    }
  }

  @Override
  public void end(@Nullable Long endTime) {
    if (!parallelized) {
      final AgentSpan activeSpan = AgentTracer.activeSpan();
      if (activeSpan == null) {
        throw new IllegalStateException(
            "No active span present, it is possible that end() was called multiple times");
      }

      if (activeSpan != this.span) {
        throw new IllegalStateException(
            "Active span does not correspond to the finished suite, "
                + "it is possible that end() was called multiple times "
                + "or an operation that was started by the suite is still in progress; "
                + "active span is: "
                + activeSpan
                + "; "
                + "expected span is: "
                + this.span);
      }

      AgentTracer.closeActive();
    }

    onSpanFinish.accept(span);

    if (endTime != null) {
      span.finish(endTime);
    } else {
      span.finish();
    }

    metricCollector.add(
        CiVisibilityCountMetric.EVENT_FINISHED, 1, instrumentation, EventType.SUITE);
  }

  @Override
  public TestImpl testStart(
      String testName, @Nullable Method testMethod, @Nullable Long startTime) {
    return testStart(testName, null, testMethod, startTime);
  }

  public TestImpl testStart(
      String testName,
      @Nullable String testParameters,
      @Nullable Method testMethod,
      @Nullable Long startTime) {
    return new TestImpl(
        moduleSpanContext,
        span.getSpanId(),
        moduleName,
        testSuiteName,
        testName,
        testParameters,
        itrCorrelationId,
        startTime,
        testClass,
        testMethod,
        instrumentationType,
        instrumentation,
        config,
        metricCollector,
        testDecorator,
        sourcePathResolver,
        linesResolver,
        codeowners,
        coverageStoreFactory,
        executionResults,
        capabilities,
        tagsPropagator::propagateStatus);
  }
}
