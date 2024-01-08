package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.TracePropagationStyle;
import java.util.LinkedHashMap;

public interface AgentPropagation {
  <C> void inject(AgentSpan span, C carrier, Setter<C> setter);

  <C> void inject(AgentSpan.Context context, C carrier, Setter<C> setter);

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

  interface Setter<C> {
    void set(C carrier, String key, String value);
  }

  interface BinarySetter<C> extends Setter<C> {
    void set(C carrier, String key, byte[] value);
  }

  <C> AgentSpan.Context.Extracted extract(C carrier, ContextVisitor<C> getter);

  interface KeyClassifier {

    boolean accept(String key, String value);
  }

  interface BinaryKeyClassifier {
    boolean accept(String key, byte[] value);
  }

  interface ContextVisitor<C> {
    void forEachKey(C carrier, KeyClassifier classifier);
  }

  interface BinaryContextVisitor<C> extends ContextVisitor<C> {
    void forEachKey(C carrier, BinaryKeyClassifier classifier);
  }
}
