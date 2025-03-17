package datadog.trace.bootstrap.instrumentation.websocket;

import datadog.trace.api.Functions;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.normalize.HttpResourceNames;
import java.util.function.Function;

public class ResourceNameExtractor {
  private static final String SPACE = " ";
  private static final String WEBSOCKET_SPACE = "websocket" + SPACE;
  private static final DDCache<CharSequence, CharSequence> CACHE = DDCaches.newFixedSizeCache(128);
  private static final Function<CharSequence, CharSequence> EXTRACTOR =
      s -> {
        if (s == null || s.length() == 0) {
          return HttpResourceNames.DEFAULT_RESOURCE_NAME;
        }
        int idx = s.toString().indexOf(SPACE);
        if (idx < 0 || idx == s.length() - 1) {
          return s;
        }
        final CharSequence ret = s.subSequence(idx + 1, s.length());
        if (ret.length() == 0) {
          return HttpResourceNames.DEFAULT_RESOURCE_NAME;
        }
        return ret;
      };
  private static final Function<CharSequence, CharSequence> ADDER =
      EXTRACTOR.andThen(new Functions.Prefix(WEBSOCKET_SPACE));

  private ResourceNameExtractor() {}

  public static CharSequence extractResourceName(CharSequence handshakeResourceName) {
    return CACHE.computeIfAbsent(handshakeResourceName, ADDER);
  }
}
