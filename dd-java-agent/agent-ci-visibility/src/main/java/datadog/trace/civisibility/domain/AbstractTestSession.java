package datadog.trace.civisibility.domain;

import static datadog.trace.api.TracePropagationStyle.NONE;
import static datadog.trace.civisibility.Constants.CI_VISIBILITY_INSTRUMENTATION_NAME;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.IdGenerationStrategy;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.execution.TestStatus;
import datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.TagValue;
import datadog.trace.api.civisibility.telemetry.tag.AgentlessLogSubmissionEnabled;
import datadog.trace.api.civisibility.telemetry.tag.AutoInjected;
import datadog.trace.api.civisibility.telemetry.tag.EarlyFlakeDetectionAbortReason;
import datadog.trace.api.civisibility.telemetry.tag.EventType;
import datadog.trace.api.civisibility.telemetry.tag.FailFastTestOrderEnabled;
import datadog.trace.api.civisibility.telemetry.tag.FailedTestReplayEnabled;
import datadog.trace.api.civisibility.telemetry.tag.HasCodeowner;
import datadog.trace.api.civisibility.telemetry.tag.IsHeadless;
import datadog.trace.api.civisibility.telemetry.tag.IsUnsupportedCI;
import datadog.trace.api.civisibility.telemetry.tag.Provider;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.Constants;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.source.LinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nullable;

public abstract class AbstractTestSession {

  protected final Provider ciProvider;
  protected final InstrumentationType instrumentationType;
  protected final AgentSpan span;
  protected final Config config;
  protected final CiVisibilityMetricCollector metricCollector;
  protected final TestDecorator testDecorator;
  protected final SourcePathResolver sourcePathResolver;
  protected final Codeowners codeowners;
  protected final LinesResolver linesResolver;
  protected final SpanTagsPropagator tagPropagator;

  public AbstractTestSession(
      String projectName,
      @Nullable Long startTime,
      InstrumentationType instrumentationType,
      Provider ciProvider,
      Config config,
      CiVisibilityMetricCollector metricCollector,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver,
      Codeowners codeowners,
      LinesResolver linesResolver) {
    this.ciProvider = ciProvider;
    this.instrumentationType = instrumentationType;
    this.config = config;
    this.metricCollector = metricCollector;
    this.testDecorator = testDecorator;
    this.sourcePathResolver = sourcePathResolver;
    this.codeowners = codeowners;
    this.linesResolver = linesResolver;

    // CI Test Cycle protocol requires session's trace ID and span ID to be the same
    IdGenerationStrategy idGenerationStrategy = config.getIdGenerationStrategy();
    DDTraceId traceId = idGenerationStrategy.generateTraceId();
    AgentSpanContext traceContext =
        new TagContext(
            CIConstants.CIAPP_TEST_ORIGIN,
            null,
            null,
            null,
            PrioritySampling.UNSET,
            null,
            NONE,
            traceId);

    AgentTracer.SpanBuilder spanBuilder =
        AgentTracer.get()
            .buildSpan(
                CI_VISIBILITY_INSTRUMENTATION_NAME, testDecorator.component() + ".test_session")
            .asChildOf(traceContext)
            .withSpanId(traceId.toLong());

    if (startTime != null) {
      spanBuilder = spanBuilder.withStartTimestamp(startTime);
    }

    span = spanBuilder.start();
    tagPropagator = new SpanTagsPropagator(span);

    span.setSpanType(InternalSpanTypes.TEST_SESSION_END);
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_TEST_SESSION);
    span.setTag(Tags.TEST_SESSION_ID, span.getTraceId());

    // setting status to skip initially,
    // as we do not know in advance whether the session will have any children
    span.setTag(Tags.TEST_STATUS, TestStatus.skip);

    span.setResourceName(projectName);

    // The backend requires all session spans to have the test command tag
    // because it is used for session fingerprint calculation.
    // We're setting it here to project name as a default that works
    // reasonably well (although this is not the real command).
    // In those cases where proper command can be determined,
    // this tag will be overridden
    span.setTag(Tags.TEST_COMMAND, projectName);

    testDecorator.afterStart(span);

    metricCollector.add(
        CiVisibilityCountMetric.EVENT_CREATED,
        1,
        EventType.SESSION,
        instrumentationType == InstrumentationType.HEADLESS ? IsHeadless.TRUE : null,
        codeowners.exist() ? HasCodeowner.TRUE : null,
        ciProvider == Provider.UNSUPPORTED ? IsUnsupportedCI.TRUE : null);

    metricCollector.add(
        CiVisibilityCountMetric.TEST_SESSION,
        1,
        ciProvider,
        config.isCiVisibilityAutoInjected() ? AutoInjected.TRUE : null,
        config.isAgentlessLogSubmissionEnabled() ? AgentlessLogSubmissionEnabled.TRUE : null,
        CIConstants.FAIL_FAST_TEST_ORDER.equalsIgnoreCase(config.getCiVisibilityTestOrder())
            ? FailFastTestOrderEnabled.TRUE
            : null);

    if (instrumentationType == InstrumentationType.MANUAL_API) {
      metricCollector.add(CiVisibilityCountMetric.MANUAL_API_EVENTS, 1, EventType.SESSION);
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
    if (endTime != null) {
      span.finish(endTime);
    } else {
      span.finish();
    }

    metricCollector.add(CiVisibilityCountMetric.EVENT_FINISHED, 1, telemetryTags());

    // flushing written traces synchronously:
    // as soon as build finish event is processed,
    // the process can be killed by the CI provider
    AgentTracer.get().flush();
  }

  private TagValue[] telemetryTags() {
    Collection<TagValue> telemetryTags = new ArrayList<>();
    telemetryTags.add(EventType.SESSION);
    if (instrumentationType == InstrumentationType.HEADLESS) {
      telemetryTags.add(IsHeadless.TRUE);
    }
    if (codeowners.exist()) {
      telemetryTags.add(HasCodeowner.TRUE);
    }
    if (ciProvider == Provider.UNSUPPORTED) {
      telemetryTags.add(IsUnsupportedCI.TRUE);
    }
    telemetryTags.addAll(additionalTelemetryTags());
    return telemetryTags.toArray(new TagValue[0]);
  }

  protected Collection<TagValue> additionalTelemetryTags() {
    Set<TagValue> tags = new HashSet<>();
    if (Constants.EFD_ABORT_REASON_FAULTY.equals(span.getTag(Tags.TEST_EARLY_FLAKE_ABORT_REASON))) {
      tags.add(EarlyFlakeDetectionAbortReason.FAULTY);
    }
    if (span.getTag(DDTags.TEST_HAS_FAILED_TEST_REPLAY) != null) {
      tags.add(FailedTestReplayEnabled.SessionMetric.TRUE);
    }
    return tags;
  }
}
