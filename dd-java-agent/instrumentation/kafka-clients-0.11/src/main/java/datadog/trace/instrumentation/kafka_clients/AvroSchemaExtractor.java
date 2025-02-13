package datadog.trace.instrumentation.kafka_clients;

import datadog.trace.api.DDTags;
import datadog.trace.api.datastreams.AgentDataStreamsMonitoring;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.util.FNV64Hash;
import java.lang.reflect.Field;
import org.apache.kafka.clients.producer.ProducerRecord;

public class AvroSchemaExtractor {
  public static void tryExtractProducer(ProducerRecord record, AgentSpan span) {
    AgentDataStreamsMonitoring dsm = AgentTracer.get().getDataStreamsMonitoring();
    if (!dsm.canSampleSchema(record.topic())) {
      return;
    }
    Integer prio = span.forceSamplingDecision();
    if (prio == null || prio <= 0) {
      // don't extract schema if span is not sampled
      return;
    }
    int weight = AgentTracer.get().getDataStreamsMonitoring().trySampleSchema(record.topic());
    if (weight == 0) {
      return;
    }
    String schema = extract(record.value());
    if (schema != null) {
      span.setTag(DDTags.SCHEMA_DEFINITION, schema);
      span.setTag(DDTags.SCHEMA_WEIGHT, weight);
      span.setTag(DDTags.SCHEMA_TYPE, "avro");
      span.setTag(DDTags.SCHEMA_OPERATION, "serialization");
      span.setTag(
          DDTags.SCHEMA_ID,
          Long.toUnsignedString(FNV64Hash.generateHash(schema, FNV64Hash.Version.v1A)));
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
