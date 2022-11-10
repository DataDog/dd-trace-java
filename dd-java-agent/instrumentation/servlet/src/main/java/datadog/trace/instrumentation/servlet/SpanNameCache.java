package datadog.trace.instrumentation.servlet;

import datadog.trace.api.Functions;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import java.util.function.Function;

public final class SpanNameCache {
  public static final DDCache<CharSequence, CharSequence> SPAN_NAME_CACHE =
      DDCaches.newUnboundedCache(16);
  public static final Function<CharSequence, CharSequence> SERVLET_PREFIX =
      new Functions.Prefix("servlet.");
}
