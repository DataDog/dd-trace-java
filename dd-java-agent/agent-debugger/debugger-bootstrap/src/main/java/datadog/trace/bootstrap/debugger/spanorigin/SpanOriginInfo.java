package datadog.trace.bootstrap.debugger.spanorigin;

import static java.lang.String.format;
import static java.util.Arrays.stream;

import datadog.trace.api.DDTags;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.DebuggerContext.SnapshotHandler;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.util.stacktrace.StackWalkerFactory;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class SpanOriginInfo {

  public static final String DD_EXIT_LOCATION_SNAPSHOT_ID = "_dd.exit_location.snapshot_id";

  private static DDCache<String, SpanOriginInfo> origins = DDCaches.newFixedSizeCache(256);

  private List<StackTraceElement> entries;

  private final Method method;
  private String signature;
  private final boolean entry;

  public SpanOriginInfo() {
    method = null;
    entry = false;
  }

  public SpanOriginInfo(Method method) {
    this.method = method;
    entry = true;
  }

  public static void entry(Method method, AgentSpan span) {
    if (InstrumenterConfig.get().isSpanOriginEnabled()) {
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
      SnapshotHandler handler = DebuggerContext.snapshotHandler;
      entries =
          StackWalkerFactory.INSTANCE.walk(
              stream ->
                  stream
                      .filter(element -> !handler.isExcluded(element.getClassName()))
                      .collect(Collectors.toList()));
      if (method != null && !entries.isEmpty()) {
        signature =
            stream(method.getParameterTypes())
                .map(Class::getName)
                .collect(Collectors.joining(",", "(", ")"));
      }
    }
    return entries;
  }

  private void apply(AgentSpan span) {
    List<StackTraceElement> entries = entries();
    if (entry) {
      StackTraceElement entry = entries.get(0);
      List<AgentSpan> spans = new ArrayList<>();
      spans.add(span);
      AgentSpan rootSpan = span.getLocalRootSpan();
      if (rootSpan != null && rootSpan.getTags().get(DDTags.DD_ENTRY_LOCATION_FILE) == null) {
        spans.add(rootSpan);
      }
      spans.forEach(
          s -> {
            if (s != null) {
              s.setTag("_dd.di.has_code_location", true);
              s.setTag(DDTags.DD_ENTRY_LOCATION_FILE, toFileName(entry.getClassName()));
              s.setTag(DDTags.DD_ENTRY_METHOD, entry.getMethodName());
              s.setTag(DDTags.DD_ENTRY_LINE, entry.getLineNumber());
              s.setTag(DDTags.DD_ENTRY_TYPE, entry.getClassName());
              s.setTag(DDTags.DD_ENTRY_METHOD_SIGNATURE, signature);
            }
          });
    } else {
      span.setTag("_dd.di.has_code_location", true);
      for (int i = 0; i < entries.size(); i++) {
        StackTraceElement element = entries.get(i);
        span.setTag(format(DDTags.DD_EXIT_LOCATION_FILE, i), toFileName(element.getClassName()));
        span.setTag(format(DDTags.DD_EXIT_LOCATION_LINE, i), element.getLineNumber());
        span.setTag(format(DDTags.DD_EXIT_LOCATION_METHOD, i), element.getMethodName());
        span.setTag(format(DDTags.DD_EXIT_LOCATION_TYPE, i), element.getClassName());
      }
    }

    String probeId = DebuggerContext.captureSnapshot(span, entry, entries.get(0));
    if (!entry) {
      span.setTag(DD_EXIT_LOCATION_SNAPSHOT_ID, probeId);
    }
  }

  public static void dump(String message, Object... args) {
    System.out.println();
    System.out.println(message);
    for (int i = 0; i < args.length; i += 2) {
      System.out.printf("\t%s: %s%n", args[i], args[i + 1]);
    }
    System.out.println();
  }

  private static String toFileName(String className) {
    return className.replace('.', '/') + ".java";
  }
}
