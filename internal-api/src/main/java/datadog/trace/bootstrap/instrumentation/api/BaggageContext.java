package datadog.trace.bootstrap.instrumentation.api;

import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.context.ImplicitContextKeyed;
import java.util.HashMap;
import java.util.Map;

public class BaggageContext implements ImplicitContextKeyed {
  private static final ContextKey<BaggageContext> CONTEXT_KEY = ContextKey.named("baggage-key");

  private final Map<String, String> baggage;
  private String baggageString;
  private boolean updatedCache;

  public BaggageContext empty() {
    return create(new HashMap<>(), "");
  }

  public static BaggageContext create(Map<String, String> baggage) {
    return new BaggageContext(baggage);
  }

  private BaggageContext(Map<String, String> baggage) {
    this.baggage = baggage;
    this.baggageString = "";
    updatedCache = false;
  }

  public static BaggageContext create(Map<String, String> baggage, String w3cHeader) {
    return new BaggageContext(baggage, w3cHeader);
  }

  private BaggageContext(Map<String, String> baggage, String baggageString) {
    this.baggage = baggage;
    this.baggageString = baggageString;
    updatedCache = true;
  }

  public void addW3CBaggage(String key, String value) {
    baggage.put(key, value);
    updatedCache = false;
  }

  public void removeW3CBaggage(String key) {
    baggage.remove(key);
    updatedCache = false;
  }

  public void setW3cBaggageHeader(String w3cHeader) {
    this.baggageString = w3cHeader;
    updatedCache = true;
  }

  public String getW3cBaggageHeader() {
    if (updatedCache) {
      return baggageString;
    }
    return null;
  }

  public Map<String, String> asMap() {
    return new HashMap<>(baggage);
  }

  public static BaggageContext fromContext(Context context) {
    return context.get(CONTEXT_KEY);
  }

  @Override
  public Context storeInto(Context context) {
    return context.with(CONTEXT_KEY, this);
  }
}
