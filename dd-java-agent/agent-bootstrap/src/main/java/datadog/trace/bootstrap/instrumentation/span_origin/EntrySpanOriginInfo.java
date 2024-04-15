package datadog.trace.bootstrap.instrumentation.span_origin;

import datadog.trace.api.DDTags;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.util.stacktrace.StackWalkerFactory;
import java.lang.reflect.Method;

public class EntrySpanOriginInfo {
  private static DDCache<String, EntrySpanOriginInfo> origins = DDCaches.newFixedSizeCache(256);

  public final LineInfo entry;

  public static void apply(Method method, AgentSpan span) {
    if (InstrumenterConfig.get().isSpanOriginEnabled()) {
      origins
          .computeIfAbsent(method.toString(), (ignored) -> new EntrySpanOriginInfo(method))
          .apply(span);
    }
  }

  public EntrySpanOriginInfo(Method method) {
    StackTraceElement element =
        StackWalkerFactory.INSTANCE.walk(new FindFirstStackTraceElement(method));
    entry = new LineInfo(method, element);
  }

  private void apply(AgentSpan span) {
    span.setTag(DDTags.DD_ENTRY_LOCATION_FILE, entry.className);
    span.setTag(DDTags.DD_ENTRY_METHOD, entry.methodName);
    span.setTag(DDTags.DD_ENTRY_LINE, entry.lineNumber);
    span.setTag(DDTags.DD_ENTRY_METHOD_SIGNATURE, entry.signature);
  }
}
