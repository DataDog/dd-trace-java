package datadog.trace.core.propagation;

import datadog.trace.api.Config;
import datadog.trace.api.TraceConfig;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Supplier;

/** Helper class used only for tests to bridge package-private classes */
public class HttpCodecTestHelper {
  // W3C Trace Context standard header names (W3CHttpCodec is package-private)
  public static final String TRACE_PARENT_KEY = W3CHttpCodec.TRACE_PARENT_KEY;
  public static final String TRACE_STATE_KEY = W3CHttpCodec.TRACE_STATE_KEY;

  public static HttpCodec.Extractor newW3cHttpCodecExtractor(
      Config config, Supplier<TraceConfig> traceConfigSupplier) {
    return W3CHttpCodec.newExtractor(config, traceConfigSupplier);
  }

  /**
   * Returns the given baggage key and value pairs, in order. A key may be repeated to exercise the
   * replacement of a value already recorded.
   */
  static List<Entry<String, String>> baggageItems(String... keysAndValues) {
    List<Entry<String, String>> items = new ArrayList<>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      items.add(new SimpleImmutableEntry<>(keysAndValues[i], keysAndValues[i + 1]));
    }
    return items;
  }

  /**
   * Returns {@code itemCount} generated baggage items, mapping {@code key0} to {@code value0}
   * through {@code key(itemCount-1)} to {@code value(itemCount-1)}.
   *
   * <p>While the indices stay below 10, every item is exactly 10 characters of key plus value,
   * which is what lets the byte limit cases state a limit as a number of items.
   */
  static List<Entry<String, String>> generateBaggageItems(int itemCount) {
    List<Entry<String, String>> items = new ArrayList<>();
    for (int i = 0; i < itemCount; i++) {
      items.add(new SimpleImmutableEntry<>("key" + i, "value" + i));
    }
    return items;
  }

  /**
   * Returns the given baggage items as one prefixed header per item, preserving order.
   *
   * <p>Each item needs its own header, so a repeated key is spelled by upper casing it: this only
   * carries the same baggage key for the propagation styles that lowercase the keys they read.
   */
  static Map<String, String> otBaggageHeaders(String prefix, List<Entry<String, String>> items) {
    Map<String, String> headers = new LinkedHashMap<>();
    for (Entry<String, String> item : items) {
      String key = item.getKey();
      String header = prefix + (headers.containsKey(prefix + key) ? key.toUpperCase() : key);
      if (headers.containsKey(header)) {
        throw new IllegalArgumentException("Cannot repeat the baggage key " + key + " any further");
      }
      headers.put(header, item.getValue());
    }
    return headers;
  }

  static Map<String, String> headers(String... headerKeysAndValues) {
    HashMap<String, String> headers = new HashMap<>();
    for (int i = 0; i < headerKeysAndValues.length / 2; i++) {
      String headerValue = headerKeysAndValues[i * 2 + 1];
      if (headerValue == null) {
        continue;
      }
      String headerName = headerKeysAndValues[i * 2].toUpperCase();
      headers.put(headerName, headerValue);
    }
    return headers;
  }
}
