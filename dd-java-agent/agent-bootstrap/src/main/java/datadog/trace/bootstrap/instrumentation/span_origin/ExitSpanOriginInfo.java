package datadog.trace.bootstrap.instrumentation.span_origin;

import static java.lang.String.format;

import datadog.trace.api.DDTags;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.util.stacktrace.StackWalkerFactory;
import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Collectors;

public class ExitSpanOriginInfo {
  public static DDCache<String, ExitSpanOriginInfo> origins = DDCaches.newFixedSizeCache(256);

  private final List<Object[]> entries;

  public static void apply(Method method, AgentSpan span) {
    if (InstrumenterConfig.get().isSpanOriginEnabled()) {
      origins
          .computeIfAbsent(method.toString(), (key) -> new ExitSpanOriginInfo(method))
          .apply(span);
    }
  }

  public ExitSpanOriginInfo(Method method) {
    entries =
        StackWalkerFactory.INSTANCE.walk(
            stream ->
                stream
                    .filter(
                        // 3rd party detection rules go here
                        element ->
                            element.getClassName().startsWith("org.springframework.samples")
                                || !element.getClassName().startsWith("org.springframework")
                                    && !element.getClassName().startsWith("org.apache")
                                    && !element.getClassName().startsWith("java.")
                                    && !element.getClassName().startsWith("javax.")
                                    && !element.getClassName().startsWith("jdk."))
                    .map(element -> new Object[] {element.getClassName(), element.getLineNumber()})
                    .collect(Collectors.toList()));
  }

  private void apply(AgentSpan span) {
    int[] i = {0};
    entries.forEach(
        element -> {
          span.setTag(format(DDTags.DD_EXIT_LOCATION_FILE, i[0]), element[0]);
          span.setTag(format(DDTags.DD_EXIT_LOCATION_LINE, i[0]++), element[1]);
        });
  }
}
