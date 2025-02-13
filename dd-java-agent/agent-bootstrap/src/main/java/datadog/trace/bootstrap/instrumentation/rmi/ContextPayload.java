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
    try {
      final Object object = oi.readObject();
      if (object instanceof Map) {
        return new ContextPayload((Map<String, String>) object);
      }
    } catch (final ClassCastException | ClassNotFoundException ex) {
      log.debug("Error reading object", ex);
    }

    return null;
  }

  public void write(final ObjectOutput out) throws IOException {
    out.writeObject(context);
  }

  @ParametersAreNonnullByDefault
  public static class InjectAdapter implements CarrierSetter<ContextPayload> {
    @Override
    public void set(final ContextPayload carrier, final String key, final String value) {
      carrier.getContext().put(key, value);
    }
  }
}
