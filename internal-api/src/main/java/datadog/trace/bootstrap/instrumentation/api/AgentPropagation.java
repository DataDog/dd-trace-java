package datadog.trace.bootstrap.instrumentation.api;

import static datadog.context.propagation.Concern.named;
import static datadog.context.propagation.Concern.withPriority;

import datadog.context.Context;
import datadog.context.propagation.CarrierSetter;
import datadog.context.propagation.CarrierVisitor;
import datadog.context.propagation.Concern;
import datadog.context.propagation.Propagators;
import java.util.function.BiConsumer;
import javax.annotation.ParametersAreNonnullByDefault;

public interface AgentPropagation {
  Concern TRACING_CONCERN = named("tracing");
  Concern BAGGAGE_CONCERN = Concern.named("baggage");
  Concern XRAY_TRACING_CONCERN = named("tracing-xray");
  Concern STANDALONE_ASM_CONCERN = named("asm-standalone");
  // TODO DSM propagator should run after the other propagators as it stores the pathway context
  // TODO into the span context for now. Remove priority after the migration is complete.
  Concern DSM_CONCERN = withPriority("data-stream-monitoring", 110);

  interface Setter<C> extends CarrierSetter<C> {
    void set(C carrier, String key, String value);
  }

  default <C> AgentSpanContext.Extracted extract(final C carrier, final ContextVisitor<C> getter) {
    Context extracted = Propagators.defaultPropagator().extract(Context.root(), carrier, getter);
    AgentSpan extractedSpan = AgentSpan.fromContext(extracted);
    return extractedSpan == null ? null : (AgentSpanContext.Extracted) extractedSpan.context();
  }

  interface KeyClassifier {
    boolean accept(String key, String value);
  }

  interface ContextVisitor<C> extends CarrierVisitor<C> {
    void forEachKey(C carrier, KeyClassifier classifier);

    @ParametersAreNonnullByDefault
    @Override
    default void forEachKeyValue(C carrier, BiConsumer<String, String> visitor) {
      forEachKey(
          carrier,
          (key, value) -> {
            visitor.accept(key, value);
            return true;
          });
    }
  }
}
