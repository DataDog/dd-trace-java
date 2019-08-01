package datadog.trace.instrumentation.api;

import datadog.trace.context.TraceScope;
import java.util.List;

public interface Propagation {

  TraceScope.Continuation capture();

  <C> void inject(AgentSpan span, C carrier, Setter<C> setter);

  interface Setter<C> {
    void set(C carrier, String key, String value);
  }

  <C> AgentSpan.Context extract(C carrier, Getter<C> getter);

  interface Getter<C> {
    List<String> keys(C carrier);

    String get(C carrier, String key);
  }
}
