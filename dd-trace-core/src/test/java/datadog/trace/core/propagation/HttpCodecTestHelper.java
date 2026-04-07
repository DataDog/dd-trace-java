package datadog.trace.core.propagation;

import datadog.trace.api.Config;
import datadog.trace.api.TraceConfig;
import java.util.function.Supplier;

/** Helper class used only for tests to bridge package-private classes */
public class HttpCodecTestHelper {
  public static HttpCodec.Extractor W3CHttpCodecNewExtractor(
      Config config, Supplier<TraceConfig> traceConfigSupplier) {
    return W3CHttpCodec.newExtractor(config, traceConfigSupplier);
  }
}
