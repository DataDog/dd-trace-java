package datadog.trace.lambda;

import static datadog.trace.lambda.LambdaEventData.first;
import static datadog.trace.lambda.LambdaEventData.object;
import static datadog.trace.lambda.LambdaEventData.payload;
import static datadog.trace.lambda.LambdaEventData.string;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Ports Bottlecap's trigger matching order, metadata and wrapped-event inference to Java. */
final class LambdaTriggerExtractor {
  private static final Logger log = LoggerFactory.getLogger(LambdaTriggerExtractor.class);
  private final String region;
  private final String functionName;
  private final Map<String, String> serviceMapping;

  LambdaTriggerExtractor(String region, String functionName, Map<String, String> serviceMapping) {
    this.region = region;
    this.functionName = functionName;
    this.serviceMapping = serviceMapping;
  }

  LambdaTrigger extract(Object event) {
    try {
      LambdaTrigger trigger = identify(payload(event));
      if (trigger != null) {
        decorate(trigger);
        return trigger;
      }
    } catch (RuntimeException error) {
      log.debug("Cannot infer Lambda event spans", error);
    }
    return new LambdaTrigger(null, "", false);
  }

  private LambdaTrigger identify(Map<String, Object> event) {
    LambdaTrigger http = LambdaHttpEvents.infer(event, region, functionName, serviceMapping);
    if (http != null) {
      return http;
    }
    if (event.containsKey("records")) {
      return LambdaMessagingEvents.msk(event, serviceMapping);
    }
    Map<String, Object> record = first(event.get("Records"));
    if ("aws:sqs".equals(record.get("eventSource"))) {
      return LambdaMessagingEvents.sqs(record, region, serviceMapping);
    }
    if (record.containsKey("Sns")) {
      return LambdaMessagingEvents.sns(
          object(record.get("Sns")), string(record, "EventSubscriptionArn"), serviceMapping);
    }
    if (record.containsKey("dynamodb")) {
      return LambdaRecordEvents.dynamodb(record, serviceMapping);
    }
    if (record.containsKey("s3")) {
      return LambdaRecordEvents.s3(record, serviceMapping);
    }
    if (event.containsKey("detail-type")) {
      return LambdaMessagingEvents.eventBridge(event, serviceMapping);
    }
    if (record.containsKey("kinesis")) {
      return LambdaRecordEvents.kinesis(record, serviceMapping);
    }
    return LambdaGeneratedContext.stepFunctions(event);
  }

  private static void decorate(LambdaTrigger trigger) {
    if (trigger.name != null) {
      trigger.meta.put("operation_name", trigger.name);
      trigger.meta.put("_inferred_span.tag_source", "self");
      trigger.meta.put("_inferred_span.synchronicity", trigger.async ? "async" : "sync");
    }
    if (trigger.wrapped != null) {
      // Bottlecap emits at most two inferred service spans, even for deeper envelopes.
      trigger.wrapped.wrapped = null;
      decorate(trigger.wrapped);
      for (Map.Entry<String, String> tag : trigger.wrapped.tags.entrySet()) {
        if (!"function_trigger.event_source_arn".equals(tag.getKey())) {
          trigger.meta.putIfAbsent(tag.getKey(), tag.getValue());
        }
      }
    }
  }
}
