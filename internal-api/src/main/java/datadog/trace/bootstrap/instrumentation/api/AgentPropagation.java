package datadog.trace.bootstrap.instrumentation.api;

import datadog.context.propagation.CarrierSetter;
import datadog.context.propagation.CarrierVisitor;
import datadog.context.propagation.Concern;
import datadog.trace.api.TracePropagationStyle;
import java.util.LinkedHashMap;
import java.util.function.BiConsumer;
import javax.annotation.ParametersAreNonnullByDefault;

public interface AgentPropagation {
  Concern TRACING_CONCERN = Concern.named("tracing");

  <C> void inject(AgentSpan span, C carrier, Setter<C> setter);

  <C> void inject(AgentSpanContext context, C carrier, Setter<C> setter);

  <C> void inject(AgentSpan span, C carrier, Setter<C> setter, TracePropagationStyle style);

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

  <C> AgentSpanContext.Extracted extract(C carrier, ContextVisitor<C> getter);

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
