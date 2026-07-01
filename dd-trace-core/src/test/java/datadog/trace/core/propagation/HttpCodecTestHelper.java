package datadog.trace.core.propagation;

import datadog.trace.api.Config;
import datadog.trace.api.TraceConfig;
import java.util.HashMap;
import java.util.Map;
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
