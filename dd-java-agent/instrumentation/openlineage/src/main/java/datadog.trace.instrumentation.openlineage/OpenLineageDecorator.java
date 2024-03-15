package datadog.trace.instrumentation.openlineage;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static io.openlineage.client.OpenLineage.RunEvent.EventType.*;

import datadog.trace.api.DDTags;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.bootstrap.instrumentation.api.SpanLink;
import io.openlineage.client.OpenLineage;
import io.openlineage.client.OpenLineageClientUtils;

import java.util.HashMap;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OpenLineageDecorator {
  private static final Logger log = LoggerFactory.getLogger(OpenLineageDecorator.class);
  private static final HashMap<UUID, AgentSpan> spans = new HashMap<>();

  public static void onEvent(OpenLineage.RunEvent event) {
    log.info(OpenLineageClientUtils.toJson(event));

    UUID runId = event.getRun().getRunId();
    long timeMicros = event.getEventTime().toInstant().toEpochMilli() * 1000;

    AgentSpan span;
    if (event.getEventType() == START) {
      span = startSpan("openlineage", "openlineage.job", timeMicros);
      spans.put(runId, span);
    } else {
      span = spans.get(runId);
    }

    if (span == null) {
      return;
    }

    try {
      addOpenLineageMetadata(span, event);
    } catch (Exception e) {
      log.info("Exception while adding open lineage metadata", e);
    }
    if (event.getEventType() == COMPLETE
        || event.getEventType() == ABORT
        || event.getEventType() == FAIL) {
      spans.remove(runId);
      span.finish(timeMicros);
    }
  }

  public static void addOpenLineageMetadata(AgentSpan span, OpenLineage.RunEvent event) {
    span.setTag("openlineage.full_event", OpenLineageClientUtils.toJson(event));
    span.setTag("openlineage.job.run.id", event.getRun().getRunId());

    if (event.getRun().getFacets() != null && event.getRun().getFacets().getErrorMessage() != null) {
      OpenLineage.ErrorMessageRunFacet error = event.getRun().getFacets().getErrorMessage();

      span.setTag("openlineage.job.run.error.message", error.getMessage());
      span.setTag("openlineage.job.run.error.stacktrace", error.getStackTrace());
      span.setTag("openlineage.job.run.error.programming_language", error.getProgrammingLanguage());

      span.setError(true);
      span.setErrorMessage(error.getMessage());
      span.setTag(DDTags.ERROR_STACK, error.getStackTrace());
    }

    span.setTag("openlineage.job.name", event.getJob().getName());
    span.setTag("openlineage.job.namespace", event.getJob().getNamespace());
    span.setTag("openlineage.inputs", OpenLineageClientUtils.toJson(event.getInputs()));
    span.setTag("openlineage.outputs", OpenLineageClientUtils.toJson(event.getOutputs()));

    // Force keeping all those spans for now
    span.setSamplingPriority(PrioritySampling.USER_KEEP, SamplingMechanism.DATA_JOBS);

    // Set parent run if exists
    if (event.getRun().getFacets() != null && event.getRun().getFacets().getParent() != null) {
      UUID parentRunId = event.getRun().getFacets().getParent().getRun().getRunId();
      AgentSpan jobTraceSpan = spans.get(parentRunId);
      if (jobTraceSpan != null) {
        jobTraceSpan.addLink(SpanLink.from(span.context()));
      }
    }
  }
}
