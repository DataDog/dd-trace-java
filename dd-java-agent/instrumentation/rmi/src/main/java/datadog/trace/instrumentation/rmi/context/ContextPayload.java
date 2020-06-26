package datadog.trace.instrumentation.rmi.context;

import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.KeyClassifier.IGNORE;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.CachingContextVisitor;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/** ContextPayload wraps context information shared between client and server */
@Slf4j
public class ContextPayload {
  @Getter private final Map<String, String> context;
  public static final ExtractAdapter GETTER = new ExtractAdapter();
  public static final InjectAdapter SETTER = new InjectAdapter();

  public ContextPayload() {
    context = new HashMap<>();
  }

  public ContextPayload(final Map<String, String> context) {
    this.context = context;
  }

  public static ContextPayload from(final AgentSpan span) {
    final ContextPayload payload = new ContextPayload();
    propagate().inject(span, payload, SETTER);
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

  public static class ExtractAdapter extends CachingContextVisitor<ContextPayload> {

    @Override
    public void forEachKey(
        ContextPayload carrier,
        AgentPropagation.KeyClassifier classifier,
        AgentPropagation.KeyValueConsumer consumer) {
      for (Map.Entry<String, String> entry : carrier.getContext().entrySet()) {
        String lowerCaseKey = toLowerCase(entry.getKey());
        int classification = classifier.classify(lowerCaseKey);
        if (classification != IGNORE) {
          if (!consumer.accept(classification, lowerCaseKey, entry.getValue())) {
            return;
          }
        }
      }
    }
  }

  public static class InjectAdapter implements AgentPropagation.Setter<ContextPayload> {
    @Override
    public void set(final ContextPayload carrier, final String key, final String value) {
      carrier.getContext().put(key, value);
    }
  }
}
