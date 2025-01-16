package datadog.trace.core.propagation;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class W3CBaggageInjector {
  private static final Logger LOGGER = LoggerFactory.getLogger(W3CBaggageInjector.class);

  public <C> void inject(AgentSpan span, C carrier, AgentPropagation.Setter<C> setter) {
    StringBuilder builder = new StringBuilder();

    for (final Map.Entry<String, String> baggageItem : span.context().baggageItems()) {
      // quick and dirty implementation for now
      if (builder.length() > 0) {
        builder.append(",");
      }

      // TODO: encode baggageItem.getValue() to be W3C compliant
      builder.append(baggageItem.getKey())
          .append('=')
          .append(HttpCodec.encodeBaggage(baggageItem.getValue()));
    }

    setter.set(carrier, "baggage", builder.toString());
  }
}

