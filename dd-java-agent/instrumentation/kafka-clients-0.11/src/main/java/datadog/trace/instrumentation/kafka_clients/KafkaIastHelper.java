package datadog.trace.instrumentation.kafka_clients;

import static datadog.trace.api.iast.SourceTypes.KAFKA_MESSAGE_KEY;
import static datadog.trace.api.iast.SourceTypes.KAFKA_MESSAGE_VALUE;

import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.taint.TaintedObjects;
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

  public static TaintedObjects beforeDeserialize(
      final ContextStore<Deserializer, Boolean> store,
      final Deserializer<?> deserializer,
      final Object data) {
    CallDepthThreadLocalMap.incrementCallDepth(Deserializer.class);
    if (data == null) {
      return null;
    }
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module == null) {
      return null;
    }
    final TaintedObjects to = IastContext.Provider.taintedObjects();
    if (to == null) {
      return null;
    }
    if (module.isTainted(to, data)) {
      return to; // prevent double tainting on reentrant calls
    }
    final byte source = getSource(store, deserializer);
    module.taintObject(to, data, source);
    return to;
  }

  public static TaintedObjects beforeDeserialize(
      final ContextStore<Deserializer, Boolean> store,
      final Deserializer<?> deserializer,
      final ByteBuffer data) {
    CallDepthThreadLocalMap.incrementCallDepth(Deserializer.class);
    if (data == null || data.remaining() == 0) {
      return null;
    }
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module == null) {
      return null;
    }
    final TaintedObjects to = IastContext.Provider.taintedObjects();
    if (to == null) {
      return null;
    }
    if (module.isTainted(to, data)) {
      return to; // prevent double tainting on reentrant calls
    }
    final byte source = getSource(store, deserializer);
    int start = data.position();
    if (data.hasArray()) {
      start += data.arrayOffset();
    }
    module.taintObjectRange(to, data, source, start, data.remaining());
    return to;
  }

  public static void afterDeserialize(
      final TaintedObjects to,
      final ContextStore<Deserializer, Boolean> store,
      final Deserializer<?> deserializer,
      final Object result) {
    // final exit of the method
    if (CallDepthThreadLocalMap.decrementCallDepth(Deserializer.class) != 0) {
      return;
    }
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module == null) {
      return;
    }
    final byte source = getSource(store, deserializer);
    module.taintObject(to, result, source);
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
