package datadog.trace.instrumentation.kafka_clients38;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.BufferedHeaderFields;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Makes Kafka's {@code RecordHeader} implement {@link BufferedHeaderFields}, so extraction can read
 * a fetched header's key and value from the record batch without {@code key()} decoding and {@code
 * value()} copying them. A {@code RecordHeader} loaded before the agent, or with other fields, is
 * left alone, and extraction reads it through {@code key()} and {@code value()}. The fields are
 * also checked by muzzle (generated from {@code installFieldAccessors}), against every Kafka
 * version in range.
 */
@AutoService(InstrumenterModule.class)
public final class RecordHeaderInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.WithStructuralChange {

  public RecordHeaderInstrumentation() {
    super("kafka", "kafka-3.8");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return hasClassNamed("org.apache.kafka.clients.MetadataRecoveryStrategy"); // since 3.8
  }

  @Override
  public String instrumentedType() {
    return "org.apache.kafka.common.header.internals.RecordHeader";
  }

  @Override
  public Class<?> structuralChangeMarker() {
    return BufferedHeaderFields.class;
  }

  @Override
  public void typeAdvice(TypeTransformer transformer) {
    transformer.installFieldAccessors(BufferedHeaderFields.class);
  }
}
