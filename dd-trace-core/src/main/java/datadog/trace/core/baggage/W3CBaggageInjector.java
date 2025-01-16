package datadog.trace.core.baggage;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class W3CBaggageInjector {
  private static final Logger LOGGER = LoggerFactory.getLogger(W3CBaggageInjector.class);

  public <C> void inject(AgentSpan span, C carrier, AgentPropagation.Setter<C> setter) {
    Iterable<Map.Entry<String, String>> baggageItems = span.context().baggageItems();
    // get span.context().baggageItems() and build the baggage header value like "key1=value1,key2=value2"

    String headerValue = "TODO";
    setter.set(carrier, "baggage", headerValue);
  }
}

