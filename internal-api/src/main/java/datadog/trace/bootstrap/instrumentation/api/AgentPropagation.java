package datadog.trace.bootstrap.instrumentation.api;

import static datadog.context.propagation.Concern.named;

import datadog.context.Context;
import datadog.context.propagation.CarrierSetter;
import datadog.context.propagation.CarrierVisitor;
import datadog.context.propagation.Concern;
import datadog.context.propagation.Propagators;
import java.util.LinkedHashMap;
import java.util.function.BiConsumer;
import javax.annotation.ParametersAreNonnullByDefault;

public interface AgentPropagation {
  Concern TRACING_CONCERN = named("tracing");
  Concern XRAY_TRACING_CONCERN = named("tracing-xray");
  Concern STANDALONE_ASM_CONCERN = named("asm-standalone");

  // The input tags should be sorted.
  <C> void injectPathwayContext(
      AgentSpan span, C carrier, Setter<C> setter, LinkedHashMap<String, String> sortedTags);

  <C> void injectPathwayContext(
      AgentSpan span,
      C carrier,
      Setter<C> setter,
      LinkedHashMap<String, String> sortedTags,
      long defaultTimestamp,
      long payloadSizeBytes);

  <C> void injectPathwayContextWithoutSendingStats(
      AgentSpan span, C carrier, Setter<C> setter, LinkedHashMap<String, String> sortedTags);

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
