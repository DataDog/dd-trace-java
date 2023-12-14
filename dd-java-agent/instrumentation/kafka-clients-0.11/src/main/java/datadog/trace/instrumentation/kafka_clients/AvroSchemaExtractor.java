package datadog.trace.instrumentation.kafka_clients;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.lang.reflect.Field;
import org.apache.kafka.clients.producer.ProducerRecord;

public class AvroSchemaExtractor {
  public static void tryExtract(ProducerRecord record, AgentSpan span) {
    Integer prio = span.forceSamplingDecision();
    if (prio == null || prio <= 0) {
      // don't extract schema if span is not sampled
      return;
    }
    int weight = AgentTracer.get().getDataStreamsMonitoring().shouldSampleSchema(record.topic());
    if (weight == 0) {
      return;
    }
    String schema = extract(record.value());
    if (schema != null) {
      span.setTag("messaging.kafka.value_schema.definition", schema);
      span.setTag("messaging.kafka.value_schema.weight", weight);
      span.setTag("messaging.kafka.value_schema.type", "avro");
      span.setTag("messaging.kafka.value_schema.id", schema.hashCode());
    }
  }

  public static String extract(Object value) {
    if (value == null) {
      return null;
    }
    try {
      Class<?> clazz = value.getClass();
      Field field = clazz.getDeclaredField("schema");
      field.setAccessible(true);
      return field.get(value).toString();
    } catch (NoSuchFieldException | IllegalAccessException e) {
      return null;
    }
  }
}
