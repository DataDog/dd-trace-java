package datadog.trace.instrumentation.kafka_clients;

import static datadog.trace.api.iast.SourceTypes.KAFKA_MESSAGE_KEY;
import static datadog.trace.api.iast.SourceTypes.KAFKA_MESSAGE_VALUE;

import datadog.trace.api.iast.IastContext;
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

  public static IastContext beforeDeserialize(
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
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return null;
    }
    if (module.isTainted(ctx, data)) {
      return ctx; // prevent double tainting on reentrant calls
    }
    final byte source = getSource(store, deserializer);
    if (data instanceof String) {
      module.taintString(ctx, (String) data, source);
    } else {
      module.taintObject(ctx, data, source);
    }
    return ctx;
  }

  public static IastContext beforeDeserialize(
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
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return null;
    }
    if (module.isTainted(ctx, data)) {
      return ctx; // prevent double tainting on reentrant calls
    }
    final byte source = getSource(store, deserializer);
    int start = data.position();
    if (data.hasArray()) {
      start += data.arrayOffset();
    }
    module.taintObjectRange(ctx, data, source, start, data.remaining());
    return ctx;
  }

  public static void afterDeserialize(
      final IastContext ctx,
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
    if (result instanceof String) {
      module.taintString(ctx, (String) result, source);
    } else {
      module.taintObject(ctx, result, source);
    }
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
