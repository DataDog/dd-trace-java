package datadog.trace.lambda;

import static datadog.trace.api.datastreams.DataStreamsTags.Direction.INBOUND;
import static datadog.trace.lambda.LambdaEventData.get;
import static datadog.trace.lambda.LambdaEventData.last;
import static datadog.trace.lambda.LambdaEventData.object;
import static datadog.trace.lambda.LambdaEventData.string;
import static datadog.trace.lambda.LambdaEventData.values;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;

import datadog.context.Context;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.datastreams.DataStreamsContext;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.api.datastreams.PathwayContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.datastreams.DataStreamsMonitoring;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Per-record Lambda consume checkpoints, independent of trace retention. */
final class LambdaDataStreams {
  private static final Logger log = LoggerFactory.getLogger(LambdaDataStreams.class);
  private static final AtomicBoolean flushWarning = new AtomicBoolean();

  static void consume(
      CoreTracer tracer, Config config, AgentSpan invocation, Map<String, Object> event) {
    if (!invocation.traceConfig().isDataStreamsEnabled()) {
      return;
    }
    try {
      List<Input> inputs = inputs(config, event);
      if (inputs.isEmpty()) {
        return;
      }
      DataStreamsMonitoring dsm = (DataStreamsMonitoring) tracer.getDataStreamsMonitoring();
      boolean attached = false;
      for (Input input : inputs) {
        Context extracted =
            dsm.propagator()
                .extract(
                    Context.root(), input.carrier, (carrier, visitor) -> carrier.forEach(visitor));
        AgentSpan parent = AgentSpan.fromContext(extracted);
        PathwayContext pathway = parent == null ? null : parent.spanContext().getPathwayContext();
        if (pathway == null) {
          pathway = dsm.newPathwayContext();
        }
        pathway.setCheckpoint(DataStreamsContext.create(input.tags, 0, input.bytes), dsm::add);
        // A batch has many inbound edges; child work continues the first record's pathway.
        if (!attached && pathway.isStarted()) {
          invocation.spanContext().mergePathwayContext(pathway);
          invocation.setTag(DDTags.PATHWAY_HASH, Long.toUnsignedString(pathway.getHash()));
          attached = true;
        }
      }
    } catch (RuntimeException error) {
      log.debug("Unable to process native Lambda DSM consumption", error);
    }
  }

  static void flush(CoreTracer tracer, AgentSpan invocation) {
    if (invocation.traceConfig().isDataStreamsEnabled()
        && !tracer.getDataStreamsMonitoring().flush()
        && flushWarning.compareAndSet(false, true)) {
      log.warn("Native Lambda DSM flush did not complete successfully before handler return");
    }
  }

  static List<Input> inputs(Config config, Map<String, Object> event) {
    List<Input> result = new ArrayList<>();
    LambdaTriggerExtractor extractor = new LambdaTriggerExtractor("", "", emptyMap());
    for (Object value : values(event.get("Records"))) {
      Map<String, Object> record = object(value);
      LambdaTrigger trigger = extractor.extract(singletonMap("Records", singletonList(record)));
      if ("aws:sqs".equals(record.get("eventSource")) && trigger.name != null) {
        result.add(
            new Input(
                DataStreamsTags.create("sqs", INBOUND, last(string(record, "eventSourceARN"), ":")),
                trigger.carrier,
                bytes(string(record, "body"))));
      } else if (record.containsKey("Sns") && trigger.name != null) {
        result.add(
            new Input(
                DataStreamsTags.create("sns", INBOUND, string(record, "Sns", "TopicArn")),
                trigger.carrier,
                bytes(string(record, "Sns", "Message"))));
      } else if (record.containsKey("kinesis") && trigger.name != null) {
        result.add(
            new Input(
                DataStreamsTags.create(
                    "kinesis", INBOUND, last(string(record, "eventSourceARN"), "/")),
                trigger.carrier,
                decodedSize(string(record, "kinesis", "data"))));
      }
    }
    for (Object group : new TreeMap<>(object(event.get("records"))).values()) {
      for (Object value : values(group)) {
        Map<String, Object> record = object(value);
        if (!(record.get("topic") instanceof String)) {
          continue;
        }
        result.add(
            new Input(
                DataStreamsTags.createWithGroup(
                    "kafka", INBOUND, string(record, "topic"), config.getDsmKafkaGroup()),
                LambdaMessagingEvents.kafkaHeaders(record.get("headers")),
                decodedSize(string(record, "value"))));
      }
    }
    LambdaTrigger trigger = extractor.extract(event);
    if ("aws.eventbridge".equals(trigger.name)) {
      String exchange = config.getDsmExchangeName();
      if (exchange == null) {
        exchange = "default";
        for (Object value : values(event.get("resources"))) {
          if (!(value instanceof String)) {
            continue;
          }
          String[] parts = ((String) value).split(":rule/", 2);
          if (parts.length == 2 && parts[1].contains("/") && !parts[1].startsWith("/")) {
            exchange = parts[1].substring(0, parts[1].indexOf('/'));
            break;
          }
        }
      }
      DataStreamsTags tags =
          new DataStreamsTags(
              null,
              INBOUND,
              exchange,
              string(event, "detail-type"),
              "eventbridge",
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null);
      result.add(
          new Input(
              tags, trigger.carrier, bytes(LambdaEventData.serializeValue(get(event, "detail")))));
    }
    return result;
  }

  private static int bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
  }

  private static int decodedSize(String value) {
    try {
      return Base64.getDecoder().decode(value).length;
    } catch (IllegalArgumentException ignored) {
      return 0;
    }
  }

  static final class Input {
    final DataStreamsTags tags;
    final Map<String, String> carrier;
    final long bytes;

    Input(DataStreamsTags tags, Map<String, String> carrier, long bytes) {
      this.tags = tags;
      this.carrier = carrier;
      this.bytes = bytes;
    }
  }
}
