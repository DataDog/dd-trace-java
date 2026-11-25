package datadog.trace.lambda;

import static datadog.trace.lambda.LambdaEventData.decode;
import static datadog.trace.lambda.LambdaEventData.get;
import static datadog.trace.lambda.LambdaEventData.headers;
import static datadog.trace.lambda.LambdaEventData.json;
import static datadog.trace.lambda.LambdaEventData.last;
import static datadog.trace.lambda.LambdaEventData.micros;
import static datadog.trace.lambda.LambdaEventData.object;
import static datadog.trace.lambda.LambdaEventData.part;
import static datadog.trace.lambda.LambdaEventData.partition;
import static datadog.trace.lambda.LambdaEventData.service;
import static datadog.trace.lambda.LambdaEventData.string;
import static datadog.trace.lambda.LambdaEventData.tag;
import static datadog.trace.lambda.LambdaEventData.timestamp;
import static datadog.trace.lambda.LambdaEventData.values;
import static java.util.Collections.emptyMap;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Bottlecap's message-carrier precedence and inferred messaging spans. */
final class LambdaMessagingEvents {
  private LambdaMessagingEvents() {}

  static LambdaTrigger sqs(Map<String, Object> record, String region, Map<String, String> mapping) {
    String sourceArn = string(record, "eventSourceARN");
    if (sourceArn.isEmpty()) {
      return null;
    }
    String queue = last(sourceArn, ":");
    Map<String, Object> attributes = object(record.get("attributes"));
    String arn =
        "arn:" + partition(region) + ":sqs:" + region + ":" + part(sourceArn, ":", 4) + ":" + queue;
    LambdaTrigger trigger =
        new LambdaTrigger("sqs", arn, true)
            .span(
                "aws.sqs",
                service(mapping, queue, "lambda_sqs", queue),
                queue,
                micros(attributes.get("SentTimestamp"), 1_000));
    trigger.mapping(mapping, queue, "lambda_sqs");
    trigger.meta.put("receipt_handle", string(record, "receiptHandle"));
    trigger.meta.put("retry_count", string(attributes, "ApproximateReceiveCount"));
    trigger.meta.put("sender_id", string(attributes, "SenderId"));
    trigger.meta.put("source_arn", sourceArn);
    trigger.meta.put("aws_region", string(record, "awsRegion"));
    for (String key : new String[] {"retry_count", "sender_id", "source_arn", "aws_region"}) {
      trigger.tags.put(key, trigger.meta.get(key));
    }
    Map<String, Object> body = json(string(record, "body"));
    trigger.wrapped =
        body.containsKey("TopicArn") ? sns(body, "", mapping) : eventBridge(body, mapping);
    Map<String, Object> attribute = object(get(record, "messageAttributes", "_datadog"));
    if (!attribute.isEmpty()) {
      String type = string(attribute, "dataType");
      if (type.startsWith("String") && attribute.get("stringValue") instanceof String) {
        trigger.carrier = headers(json(string(attribute, "stringValue")));
      } else if (type.startsWith("Binary") && attribute.get("binaryValue") instanceof String) {
        trigger.carrier = headers(json(decode(string(attribute, "binaryValue"))));
      } else if (trigger.wrapped != null) {
        trigger.carrier = trigger.wrapped.carrier;
      }
    } else if (trigger.wrapped != null) {
      trigger.carrier = trigger.wrapped.carrier;
    }
    trigger.generatedContext = LambdaGeneratedContext.xray(string(attributes, "AWSTraceHeader"));
    return trigger;
  }

  static LambdaTrigger sns(
      Map<String, Object> entity, String subscriptionArn, Map<String, String> mapping) {
    String arn = string(entity, "TopicArn");
    if (arn.isEmpty()) {
      return null;
    }
    String topic = last(arn, ":");
    LambdaTrigger trigger =
        new LambdaTrigger("sns", arn, true)
            .span(
                "aws.sns",
                service(mapping, topic, "lambda_sns", topic),
                topic,
                timestamp(string(entity, "Timestamp")));
    trigger.mapping(mapping, topic, "lambda_sns");
    trigger.meta.put("topicname", topic);
    trigger.meta.put("topic_arn", arn);
    trigger.meta.put("message_id", string(entity, "MessageId"));
    trigger.meta.put("type", string(entity, "Type"));
    tag(trigger.meta, "subject", entity.get("Subject"));
    if (!subscriptionArn.isEmpty()) {
      trigger.meta.put("event_subscription_arn", subscriptionArn);
    }
    trigger.wrapped = eventBridge(json(string(entity, "Message")), mapping);
    Map<String, Object> attribute = object(get(entity, "MessageAttributes", "_datadog"));
    if (!attribute.isEmpty()) {
      String type = string(attribute, "Type");
      if ("String".equals(type)) {
        trigger.carrier = headers(json(string(attribute, "Value")));
      } else if ("Binary".equals(type)) {
        trigger.carrier = headers(json(decode(string(attribute, "Value"))));
      }
    } else if (trigger.wrapped != null) {
      trigger.carrier = trigger.wrapped.carrier;
    }
    return trigger;
  }

  static LambdaTrigger eventBridge(Map<String, Object> event, Map<String, String> mapping) {
    String source = string(event, "source");
    if (!event.containsKey("detail-type") || source.isEmpty() || "aws.events".equals(source)) {
      return null;
    }
    Map<String, String> carrier = headers(get(event, "detail", "_datadog"));
    String resource = carrier.getOrDefault("x-datadog-resource-name", source);
    long start = micros(carrier.get("x-datadog-start-time"), 1_000);
    if (start == 0) {
      start = timestamp(string(event, "time"));
    }
    LambdaTrigger trigger =
        new LambdaTrigger("eventbridge", source, true)
            .span(
                "aws.eventbridge",
                service(mapping, resource, "lambda_eventbridge", resource),
                resource,
                start);
    trigger.mapping(mapping, resource, "lambda_eventbridge");
    trigger.meta.put("detail_type", string(event, "detail-type"));
    trigger.carrier = carrier;
    return trigger;
  }

  static LambdaTrigger msk(Map<String, Object> event, Map<String, String> mapping) {
    Map<String, Object> chosen = emptyMap();
    Map<String, String> carrier = emptyMap();
    for (Object group : new TreeMap<>(object(event.get("records"))).values()) {
      for (Object value : values(group)) {
        Map<String, Object> record = object(value);
        if (chosen.isEmpty()) {
          chosen = record;
        }
        Map<String, String> candidate = kafkaHeaders(record.get("headers"));
        if (candidate.containsKey("x-datadog-trace-id") || candidate.containsKey("traceparent")) {
          chosen = record;
          carrier = candidate;
          break;
        }
      }
      if (!carrier.isEmpty()) {
        break;
      }
    }
    String topic = string(chosen, "topic");
    if (topic.isEmpty()) {
      return null;
    }
    if (carrier.isEmpty()) {
      carrier = kafkaHeaders(chosen.get("headers"));
    }
    String arn = string(event, "eventSourceArn");
    String cluster = part(arn, "/", 1);
    LambdaTrigger trigger =
        new LambdaTrigger("msk", arn, true)
            .span(
                "aws.msk",
                service(mapping, cluster, "lambda_msk", cluster),
                topic,
                micros(chosen.get("timestamp"), 1_000));
    trigger.mapping(mapping, cluster, "lambda_msk");
    trigger.meta.put("topic", topic);
    tag(trigger.meta, "partition", chosen.get("partition"));
    trigger.meta.put("event_source", string(event, "eventSource"));
    trigger.meta.put("event_source_arn", arn);
    trigger.carrier = carrier;
    return trigger;
  }

  static Map<String, String> kafkaHeaders(Object rawHeaders) {
    Map<String, String> result = new LinkedHashMap<>();
    for (Object header : values(rawHeaders)) {
      for (Map.Entry<String, Object> entry : object(header).entrySet()) {
        String value = headerBytes(entry.getValue());
        if (value != null) {
          result.put(entry.getKey().toLowerCase(java.util.Locale.ROOT), value);
        }
      }
    }
    return result;
  }

  private static String headerBytes(Object value) {
    try {
      Iterable<?> bytes;
      if (value instanceof Map) {
        Map<Integer, Object> ordered = new TreeMap<>();
        for (Map.Entry<String, Object> entry : object(value).entrySet()) {
          ordered.put(Integer.parseInt(entry.getKey()), entry.getValue());
        }
        bytes = ordered.values();
      } else {
        bytes = values(value);
      }
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      for (Object element : bytes) {
        int number = Integer.parseInt(LambdaEventData.scalar(element));
        if (number < -128 || number > 255) {
          return null;
        }
        output.write(number);
      }
      return new String(output.toByteArray(), StandardCharsets.UTF_8);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }
}
