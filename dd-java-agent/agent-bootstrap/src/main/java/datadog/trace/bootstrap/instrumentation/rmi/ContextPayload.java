package datadog.trace.bootstrap.instrumentation.rmi;

import static datadog.context.propagation.Propagators.defaultPropagator;

import datadog.context.propagation.CarrierSetter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.ParametersAreNonnullByDefault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** ContextPayload wraps context information shared between client and server */
public class ContextPayload {

  private static final Logger log = LoggerFactory.getLogger(ContextPayload.class);
  private static final int MAX_CONTEXT_SIZE = 1000;
  private final Map<String, String> context;
  public static final InjectAdapter SETTER = new InjectAdapter();

  public ContextPayload() {
    context = new HashMap<>();
  }

  public ContextPayload(final Map<String, String> context) {
    this.context = context;
  }

  public Map<String, String> getContext() {
    return context;
  }

  public static ContextPayload from(final AgentSpan span) {
    final ContextPayload payload = new ContextPayload();
    defaultPropagator().inject(span, payload, SETTER);
    return payload;
  }

  public static ContextPayload read(final ObjectInput oi) throws IOException {
    final int size = oi.readInt();
    if (size < 0 || size > MAX_CONTEXT_SIZE) {
      log.debug("Dropping RMI context payload: size {} exceeds maximum {}", size, MAX_CONTEXT_SIZE);
      return null;
    }
    final Map<String, String> context = new HashMap<>(size * 2);
    for (int i = 0; i < size; i++) {
      final String key = oi.readUTF();
      final String value = oi.readUTF();
      context.put(key, value);
    }
    return new ContextPayload(context);
  }

  public void write(final ObjectOutput out) throws IOException {
    out.writeInt(context.size());
    for (final Map.Entry<String, String> entry : context.entrySet()) {
      out.writeUTF(entry.getKey());
      out.writeUTF(entry.getValue());
    }
  }

  @ParametersAreNonnullByDefault
  public static class InjectAdapter implements CarrierSetter<ContextPayload> {
    @Override
    public void set(final ContextPayload carrier, final String key, final String value) {
      carrier.getContext().put(key, value);
    }
  }
}
