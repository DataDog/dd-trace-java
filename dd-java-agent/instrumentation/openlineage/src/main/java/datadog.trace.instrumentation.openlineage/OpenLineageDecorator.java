package datadog.trace.instrumentation.openlineage;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static io.openlineage.client.OpenLineage.RunEvent.EventType.*;

import datadog.trace.api.DDTags;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.core.datastreams.TagsProcessor;
import io.openlineage.client.OpenLineage;
import io.openlineage.client.OpenLineageClientUtils;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

    AgentSpan parentSpan = getParentIfExists(event);
    AgentSpan span;
    if (event.getEventType() == START) {
      if (parentSpan != null) {
        span = startSpan("openlineage", "openlineage.step", parentSpan.context(), timeMicros);
      } else {
        span = startSpan("openlineage", "openlineage.job", timeMicros);
      }
      // hack to retain 100%
      span.setTag("iast", "quick way to retain 100% of spans for now");
      spans.put(runId, span);
    } else {
      span = spans.get(runId);
    }

    if (span == null) {
      return;
    }

    for (OpenLineage.InputDataset input : event.getInputs()) {
      LinkedHashMap<String, String> sortedTags = new LinkedHashMap<>();

      sortedTags.put("ds.name", input.getName());
      sortedTags.put("ds.namespace", input.getNamespace());
      sortedTags.put(TagsProcessor.DIRECTION_TAG, TagsProcessor.DIRECTION_IN);
      sortedTags.put(TagsProcessor.TOPIC_TAG, input.getNamespace() + input.getName());
      sortedTags.put(TagsProcessor.TYPE_TAG, "openlineage");

      AgentSpan inputDatasetSpan =
          startSpan("openlineage", "openlineage.dataset", span.context(), timeMicros);
      // hack to retain 100%
      inputDatasetSpan.setTag("iast", "quick way to retain 100% of spans for now");
      inputDatasetSpan.setTag("namespace", input.getNamespace());
      inputDatasetSpan.setTag("name", input.getName());

      AgentTracer.get()
          .getDataStreamsMonitoring()
          .setCheckpoint(inputDatasetSpan, sortedTags, 0, 0);

      if (input.getFacets() != null && input.getFacets().getSchema() != null) {
        String schemaDefinition = OpenLineageClientUtils.toJson(input.getFacets().getSchema().getFields());

        inputDatasetSpan.setTag("schema.definition", schemaDefinition);
        inputDatasetSpan.setTag("schema.id", schemaDefinition.hashCode());
        inputDatasetSpan.setTag("schema.name", input.getName());
        inputDatasetSpan.setTag("schema.topic", input.getNamespace());
        inputDatasetSpan.setTag("schema.operation", "deserialization");
        inputDatasetSpan.setTag("schema.type", "json");
        inputDatasetSpan.setTag("schema.weight", 1);
        inputDatasetSpan.finish();
      }
    }

    for (OpenLineage.OutputDataset output : event.getOutputs()) {
      LinkedHashMap<String, String> sortedTags = new LinkedHashMap<>();

      sortedTags.put("ds.name", output.getName());
      sortedTags.put("ds.namespace", output.getNamespace());
      sortedTags.put(TagsProcessor.DIRECTION_TAG, TagsProcessor.DIRECTION_OUT);
      sortedTags.put(TagsProcessor.TOPIC_TAG, output.getNamespace() + output.getName());
      sortedTags.put(TagsProcessor.TYPE_TAG, "openlineage");

      AgentSpan outputDatasetSpan =
          startSpan("openlineage", "openlineage.dataset", span.context(), timeMicros);
      // hack to retain 100%
      outputDatasetSpan.setTag("iast", "quick way to retain 100% of spans for now");

      outputDatasetSpan.setTag("namespace", output.getNamespace());
      outputDatasetSpan.setTag("name", output.getName());
      AgentTracer.get()
          .getDataStreamsMonitoring()
          .setCheckpoint(outputDatasetSpan, sortedTags, 0, 0);

      if (output.getFacets() != null && output.getFacets().getSchema() != null) {
        String schemaDefinition = OpenLineageClientUtils.toJson(output.getFacets().getSchema().getFields());

        outputDatasetSpan.setTag("schema.definition", schemaDefinition);
        outputDatasetSpan.setTag("schema.id", schemaDefinition.hashCode());
        outputDatasetSpan.setTag("schema.name", output.getName());
        outputDatasetSpan.setTag("schema.topic", output.getNamespace());
        outputDatasetSpan.setTag("schema.operation", "serialization");
        outputDatasetSpan.setTag("schema.type", "json");
        outputDatasetSpan.setTag("schema.weight", 1);
        outputDatasetSpan.finish();
      }
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

    if (event.getRun().getFacets() != null
        && event.getRun().getFacets().getErrorMessage() != null) {
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
  }

  private static AgentSpan getParentIfExists(OpenLineage.RunEvent event) {
    // Set parent run if exists
    if (event.getRun().getFacets() != null && event.getRun().getFacets().getParent() != null) {
      UUID parentRunId = event.getRun().getFacets().getParent().getRun().getRunId();
      return spans.get(parentRunId);
    }
    return null;
  }
}
