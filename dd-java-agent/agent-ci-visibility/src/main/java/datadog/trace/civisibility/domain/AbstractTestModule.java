package datadog.trace.civisibility.domain;

import static datadog.trace.civisibility.Constants.CI_VISIBILITY_INSTRUMENTATION_NAME;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.execution.TestStatus;
import datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.tag.EventType;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.source.LinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import java.util.function.Consumer;
import javax.annotation.Nullable;

public abstract class AbstractTestModule {

  protected final AgentSpan span;
  protected final String moduleName;
  protected final Config config;
  protected final CiVisibilityMetricCollector metricCollector;
  protected final TestDecorator testDecorator;
  protected final SourcePathResolver sourcePathResolver;
  protected final Codeowners codeowners;
  protected final LinesResolver linesResolver;
  private final Consumer<AgentSpan> onSpanFinish;
  protected final SpanTagsPropagator tagsPropagator;

  public AbstractTestModule(
      AgentSpanContext sessionSpanContext,
      String moduleName,
      @Nullable Long startTime,
      InstrumentationType instrumentationType,
      Config config,
      CiVisibilityMetricCollector metricCollector,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver,
      Codeowners codeowners,
      LinesResolver linesResolver,
      Consumer<AgentSpan> onSpanFinish) {
    this.moduleName = moduleName;
    this.config = config;
    this.metricCollector = metricCollector;
    this.testDecorator = testDecorator;
    this.sourcePathResolver = sourcePathResolver;
    this.codeowners = codeowners;
    this.linesResolver = linesResolver;
    this.onSpanFinish = onSpanFinish;

    AgentTracer.SpanBuilder spanBuilder =
        AgentTracer.get()
            .buildSpan(
                CI_VISIBILITY_INSTRUMENTATION_NAME, testDecorator.component() + ".test_module")
            .asChildOf(sessionSpanContext);

    if (startTime != null) {
      spanBuilder = spanBuilder.withStartTimestamp(startTime);
    }

    span = spanBuilder.start();
    tagsPropagator = new SpanTagsPropagator(span);

    span.setSpanType(InternalSpanTypes.TEST_MODULE_END);
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_TEST_MODULE);

    span.setResourceName(moduleName);
    span.setTag(Tags.TEST_MODULE, moduleName);

    span.setTag(Tags.TEST_MODULE_ID, span.getSpanId());
    span.setTag(Tags.TEST_SESSION_ID, span.getTraceId());

    // setting status to skip initially,
    // as we do not know in advance whether the module will have any children
    span.setTag(Tags.TEST_STATUS, TestStatus.skip);

    testDecorator.afterStart(span);

    metricCollector.add(CiVisibilityCountMetric.EVENT_CREATED, 1, EventType.MODULE);

    if (instrumentationType == InstrumentationType.MANUAL_API) {
      metricCollector.add(CiVisibilityCountMetric.MANUAL_API_EVENTS, 1, EventType.MODULE);
    }
  }

  public void setTag(String key, Object value) {
    span.setTag(key, value);
  }

  public void setErrorInfo(Throwable error) {
    span.setError(true);
    span.addThrowable(error);
    span.setTag(Tags.TEST_STATUS, TestStatus.fail);
  }

  public void setSkipReason(String skipReason) {
    span.setTag(Tags.TEST_STATUS, TestStatus.skip);
    if (skipReason != null) {
      span.setTag(Tags.TEST_SKIP_REASON, skipReason);
    }
  }

  public void end(@Nullable Long endTime) {
    onSpanFinish.accept(span);

    if (endTime != null) {
      span.finish(endTime);
    } else {
      span.finish();
    }

    metricCollector.add(CiVisibilityCountMetric.EVENT_FINISHED, 1, EventType.MODULE);
  }
}
