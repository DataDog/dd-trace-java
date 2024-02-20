package datadog.trace.civisibility.domain;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.tag.EventType;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.InstrumentationType;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.coverage.CoverageProbeStoreFactory;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.source.MethodLinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import java.util.function.Consumer;
import javax.annotation.Nullable;

public abstract class AbstractTestModule {

  protected final AgentSpan span;
  protected final long sessionId;
  protected final String moduleName;
  protected final Config config;
  protected final CiVisibilityMetricCollector metricCollector;
  protected final TestDecorator testDecorator;
  protected final SourcePathResolver sourcePathResolver;
  protected final Codeowners codeowners;
  protected final MethodLinesResolver methodLinesResolver;
  protected final CoverageProbeStoreFactory coverageProbeStoreFactory;
  private final Consumer<AgentSpan> onSpanFinish;

  public AbstractTestModule(
      AgentSpan.Context sessionSpanContext,
      long sessionId,
      String moduleName,
      @Nullable Long startTime,
      InstrumentationType instrumentationType,
      Config config,
      CiVisibilityMetricCollector metricCollector,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver,
      Codeowners codeowners,
      MethodLinesResolver methodLinesResolver,
      CoverageProbeStoreFactory coverageProbeStoreFactory,
      Consumer<AgentSpan> onSpanFinish) {
    this.sessionId = sessionId;
    this.moduleName = moduleName;
    this.config = config;
    this.metricCollector = metricCollector;
    this.testDecorator = testDecorator;
    this.sourcePathResolver = sourcePathResolver;
    this.codeowners = codeowners;
    this.methodLinesResolver = methodLinesResolver;
    this.coverageProbeStoreFactory = coverageProbeStoreFactory;
    this.onSpanFinish = onSpanFinish;

    if (startTime != null) {
      span = startSpan(testDecorator.component() + ".test_module", sessionSpanContext, startTime);
    } else {
      span = startSpan(testDecorator.component() + ".test_module", sessionSpanContext);
    }

    span.setSpanType(InternalSpanTypes.TEST_MODULE_END);
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_TEST_MODULE);

    span.setResourceName(moduleName);
    span.setTag(Tags.TEST_MODULE, moduleName);

    span.setTag(Tags.TEST_MODULE_ID, span.getSpanId());
    span.setTag(Tags.TEST_SESSION_ID, sessionId);

    // setting status to skip initially,
    // as we do not know in advance whether the module will have any children
    span.setTag(Tags.TEST_STATUS, CIConstants.TEST_SKIP);

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
    span.setTag(Tags.TEST_STATUS, CIConstants.TEST_FAIL);
  }

  public void setSkipReason(String skipReason) {
    span.setTag(Tags.TEST_STATUS, CIConstants.TEST_SKIP);
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
