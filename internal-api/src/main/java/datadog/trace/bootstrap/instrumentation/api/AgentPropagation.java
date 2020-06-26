package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.context.TraceScope;

public interface AgentPropagation {

  TraceScope.Continuation capture();

  <C> void inject(AgentSpan span, C carrier, Setter<C> setter);

  <C> void inject(AgentSpan.Context context, C carrier, Setter<C> setter);

  interface Setter<C> {
    void set(C carrier, String key, String value);
  }

  <C> AgentSpan.Context extract(C carrier, ContextVisitor<C> getter);

  interface KeyClassifier {
    int IGNORE = -1;

    int classify(String key);
  }

  interface KeyValueConsumer {
    boolean accept(int classification, String lowerCaseKey, String value);
  }

  interface ContextVisitor<C> {
    void forEachKey(C carrier, KeyClassifier classifier, KeyValueConsumer consumer);
  }
}
