package datadog.trace.instrumentation.kafka_clients;

import static datadog.trace.api.iast.SourceTypes.KAFKA_MESSAGE_KEY;
import static datadog.trace.api.iast.SourceTypes.KAFKA_MESSAGE_VALUE;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.bootstrap.ContextStore;
import org.apache.kafka.common.serialization.Deserializer;

@SuppressWarnings("rawtypes")
public class KafkaIastHelper {

  public static void configure(
      final ContextStore<Deserializer, Boolean> store,
      final Deserializer<?> deserializer,
      final boolean isKey) {
    if (store != null) {
      store.put(deserializer, isKey);
    }
  }

  public static void taint(
      final ContextStore<Deserializer, Boolean> store,
      final Deserializer<?> deserializer,
      final byte[] data) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null) {
      byte source = KAFKA_MESSAGE_VALUE;
      if (store != null) {
        final Boolean key = store.get(deserializer);
        source = key != null && key ? KAFKA_MESSAGE_KEY : KAFKA_MESSAGE_VALUE;
      }
      module.taint(data, source);
    }
  }
}
