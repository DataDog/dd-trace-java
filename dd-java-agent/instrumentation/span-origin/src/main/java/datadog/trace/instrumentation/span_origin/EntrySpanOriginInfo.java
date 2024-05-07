package datadog.trace.instrumentation.span_origin;

import datadog.trace.api.DDTags;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.util.stacktrace.StackWalkerFactory;
import java.lang.reflect.Method;

public class EntrySpanOriginInfo {
  private static DDCache<String, EntrySpanOriginInfo> origins = DDCaches.newFixedSizeCache(256);

  public final LineInfo entry;

  public static EntrySpanOriginInfo start(Method method) {
    return origins.computeIfAbsent(method.toString(), new CreateSpanOrigin(method));
  }

  public static EntrySpanOriginInfo end(Method method) {
    return origins.computeIfAbsent(method.toString(), new FinishSpanOrigin());
  }

  public EntrySpanOriginInfo(java.lang.reflect.Method method) {
    StackTraceElement element =
        StackWalkerFactory.INSTANCE.walk(new FindFirstStackTraceElement(method));
    entry = new LineInfo(method, element);
  }

  public void applyEnd(AgentSpan span, Method method) {
    span.setTag(DDTags.DD_ENTRY_END_LINE, entry.endLineNumber(method));
  }

  public void applyStart(AgentSpan span) {
    span.setTag(DDTags.DD_ENTRY_LOCATION_FILE, entry.className);
    span.setTag(DDTags.DD_ENTRY_METHOD, entry.methodName);
    span.setTag(DDTags.DD_ENTRY_START_LINE, entry.startLineNumber);
    span.setTag(DDTags.DD_ENTRY_METHOD_SIGNATURE, entry.signature);
  }
}
