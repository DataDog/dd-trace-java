package datadog.trace.bootstrap.instrumentation.api;

import java.util.Map;

public abstract class TagsAndBaggageConsumer {
  public abstract void accept(Map<String, Object> tags, Map<String, String> baggage);
}
