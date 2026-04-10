package datadog.trace.core.propagation;

import datadog.trace.api.Config;
import datadog.trace.api.TraceConfig;
import java.util.function.Supplier;

/** Helper class used only for tests to bridge package-private classes */
public class HttpCodecTestHelper {
  // W3C Trace Context standard header names (W3CHttpCodec is package-private)
  public static final String TRACE_PARENT_KEY = W3CHttpCodec.TRACE_PARENT_KEY;
  public static final String TRACE_STATE_KEY = W3CHttpCodec.TRACE_STATE_KEY;

  public static HttpCodec.Extractor W3CHttpCodecNewExtractor(
      Config config, Supplier<TraceConfig> traceConfigSupplier) {
    return W3CHttpCodec.newExtractor(config, traceConfigSupplier);
  }
}
