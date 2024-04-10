package datadog.trace.instrumentation.protobuf_java;

import com.google.protobuf.AbstractMessage;
import com.google.protobuf.Descriptors;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentDataStreamsMonitoring;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Schema;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;

public class Decorator extends BaseDecorator {
  private static final String PROTOBUF = "protobuf";
  private final String operation;
  public static final CharSequence JAVA_PROTOBUF = UTF8BytesString.create("java-protobuf");
  private final CharSequence spanType;

  public static final Decorator SERIALIZER_DECORATOR =
      new Decorator(InternalSpanTypes.PROTOBUF, "serialization");

  public static final Decorator DESERIALIZER_DECORATOR =
      new Decorator(InternalSpanTypes.PROTOBUF, "deserialization");

  protected Decorator(CharSequence spanType, String operation) {
    this.spanType = spanType;
    this.operation = operation;
  }

  @Override
  protected CharSequence spanType() {
    return spanType;
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {PROTOBUF};
  }

  @Override
  protected CharSequence component() {
    return JAVA_PROTOBUF;
  }

  public void attachSchemaOnSpan(AbstractMessage message, AgentSpan span) {
    if (message == null) {
      return;
    }
    attachSchemaOnSpan(message.getDescriptorForType(), span);
  }

  public void attachSchemaOnSpan(Descriptors.Descriptor descriptor, AgentSpan span) {
    if (descriptor == null) {
      return;
    }
    AgentDataStreamsMonitoring dsm = AgentTracer.get().getDataStreamsMonitoring();
    span.setTag(DDTags.SCHEMA_TYPE, PROTOBUF);
    span.setTag(DDTags.SCHEMA_NAME, descriptor.getName());
    span.setTag(DDTags.SCHEMA_OPERATION, operation);
    // do a check against the schema sampler to avoid forcing the trace sampling decision too often.
    if (!dsm.canSampleSchema(operation)) {
      return;
    }
    Integer prio = span.forceSamplingDecision();
    if (prio == null || prio <= 0) {
      // don't extract schema if span is not sampled
      return;
    }
    int weight = dsm.trySampleSchema(operation);
    if (weight == 0) {
      return;
    }
    Schema schema = SchemaExtractor.extractSchemas(descriptor);
    span.setTag(DDTags.SCHEMA_DEFINITION, schema.definition);
    span.setTag(DDTags.SCHEMA_WEIGHT, weight);
    span.setTag(DDTags.SCHEMA_ID, schema.id);
  }
}
