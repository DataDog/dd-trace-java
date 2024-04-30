package datadog.trace.instrumentation.kafka_clients;

import static datadog.trace.api.iast.SourceTypes.KAFKA_MESSAGE_KEY;
import static datadog.trace.api.iast.SourceTypes.KAFKA_MESSAGE_VALUE;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.ContextStore;
import java.nio.ByteBuffer;
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
      final Object data) {
    if (CallDepthThreadLocalMap.incrementCallDepth(Deserializer.class) > 0) {
      return;
    }
    if (data == null) {
      return;
    }
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module == null) {
      return;
    }
    final byte source = getSource(store, deserializer);
    module.taintObject(data, source);
  }

  public static void taint(
      final ContextStore<Deserializer, Boolean> store,
      final Deserializer<?> deserializer,
      final ByteBuffer data) {
    if (CallDepthThreadLocalMap.incrementCallDepth(Deserializer.class) > 0) {
      return;
    }
    if (data == null || data.remaining() == 0) {
      return;
    }
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module == null) {
      return;
    }
    final byte source = getSource(store, deserializer);
    int start = data.position();
    if (data.hasArray()) {
      start += data.arrayOffset();
    }
    module.taintObjectRange(data, source, start, data.remaining());
  }

  public static void afterDeserialize() {
    CallDepthThreadLocalMap.decrementCallDepth(Deserializer.class);
  }

  private static byte getSource(
      final ContextStore<Deserializer, Boolean> store, final Deserializer<?> deserializer) {
    if (store == null) {
      return KAFKA_MESSAGE_VALUE;
    }
    final Boolean key = store.get(deserializer);
    return key != null && key ? KAFKA_MESSAGE_KEY : KAFKA_MESSAGE_VALUE;
  }
}
