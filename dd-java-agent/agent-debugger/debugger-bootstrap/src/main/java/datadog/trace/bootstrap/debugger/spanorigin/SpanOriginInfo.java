package datadog.trace.bootstrap.debugger.spanorigin;

import static java.lang.String.format;
import static java.util.Arrays.stream;

import datadog.trace.api.DDTags;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.DebuggerContext.ClassFilter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.util.stacktrace.StackWalkerFactory;
import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Collectors;

public class SpanOriginInfo {
  private static DDCache<String, SpanOriginInfo> origins = DDCaches.newFixedSizeCache(256);

  private List<StackTraceElement> entries;

  private final Method method;
  private String signature;

  public SpanOriginInfo() {
    method = null;
  }

  public SpanOriginInfo(Method method) {
    this.method = method;
  }

  public static void entry(Method method, AgentSpan span) {
    System.out.println("SpanOriginInfo.entry");
    if (InstrumenterConfig.get().isSpanOriginEnabled()) {
      System.out.println("enabled");
      origins
          .computeIfAbsent(method.toString(), (ignored) -> new SpanOriginInfo(method))
          .apply(span);
    }
  }

  public static void exit(AgentSpan span) {
    if (InstrumenterConfig.get().isSpanOriginEnabled()) {
      new SpanOriginInfo().apply(span);
    }
  }

  public List<StackTraceElement> entries() {
    if (entries == null) {
      ClassFilter filter =
          (DebuggerContext.classFilter != null)
              ? DebuggerContext.classFilter
              : fullyQualifiedClassName -> false;
      entries =
          StackWalkerFactory.INSTANCE.walk(
              stream ->
                  stream
                      .filter(element -> !filter.isDenied(element.getClassName()))
                      .collect(Collectors.toList()));
      if (method != null && !entries.isEmpty()) {
        signature =
            stream(method.getParameterTypes()).map(Class::getName).collect(Collectors.joining(","));
      }
    }
    return entries;
  }

  private void apply(AgentSpan span) {
    List<StackTraceElement> entries = entries();
    if (method != null) {
      StackTraceElement entry = entries.get(0);
      span.setTag(DDTags.DD_ENTRY_LOCATION_FILE, entry.getClassName());
      try {
        String location =
            Thread.currentThread()
                .getContextClassLoader()
                //            getClass()
                //                .getClassLoader()
                .loadClass(entry.getClassName())
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .getFile();
        System.out.println("location = " + location);
      } catch (ClassNotFoundException e) {

      }
      span.setTag(DDTags.DD_ENTRY_METHOD, entry.getMethodName());
      span.setTag(DDTags.DD_ENTRY_LINE, entry.getLineNumber());
      span.setTag(DDTags.DD_ENTRY_METHOD_SIGNATURE, signature);
    } else {
      int[] i = {0};
      entries.forEach(
          element -> {
            span.setTag(format(DDTags.DD_EXIT_LOCATION_FILE, i[0]), element.getClassName());
            span.setTag(format(DDTags.DD_EXIT_LOCATION_LINE, i[0]++), element.getLineNumber());
          });
    }

    System.out.printf(
        "trace view: https://dd.datad0g.com/dynamic-instrumentation/debug?spanID=%s&traceID=%s%n",
        span.getSpanId(), span.getTraceId());
    System.out.println("span.getTags() = " + span.getTags());

    DebuggerContext.captureSnapshot(span, entries.get(0));
  }
}
