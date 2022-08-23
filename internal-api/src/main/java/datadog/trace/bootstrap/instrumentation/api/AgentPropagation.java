package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.PropagationStyle;
import java.util.List;

public interface AgentPropagation {

  AgentScope.Continuation capture();

  <C> void inject(AgentSpan span, C carrier, Setter<C> setter);

  <C> void inject(AgentSpan.Context context, C carrier, Setter<C> setter);

  <C> void inject(AgentSpan span, C carrier, Setter<C> setter, PropagationStyle style);

  <C> void injectBinaryPathwayContext(
      AgentSpan span, C carrier, BinarySetter<C> setter, List<String> edgeTags);

  <C> void injectPathwayContext(AgentSpan span, C carrier, Setter<C> setter, List<String> edgeTags);

  interface Setter<C> {
    void set(C carrier, String key, String value);
  }

  interface BinarySetter<C> {
    void set(C carrier, String key, byte[] value);
  }

  <C> AgentSpan.Context.Extracted extract(C carrier, ContextVisitor<C> getter);

  <C> PathwayContext extractBinaryPathwayContext(C carrier, BinaryContextVisitor<C> getter);

  <C> PathwayContext extractPathwayContext(C carrier, ContextVisitor<C> getter);

  interface KeyClassifier {

    boolean accept(String key, String value);
  }

  interface BinaryKeyClassifier {
    boolean accept(String key, byte[] value);
  }

  interface ContextVisitor<C> {
    void forEachKey(C carrier, KeyClassifier classifier);
  }

  interface BinaryContextVisitor<C> {
    void forEachKey(C carrier, BinaryKeyClassifier classifier);
  }
}
