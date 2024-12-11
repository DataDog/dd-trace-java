package datadog.trace.core.util;

import datadog.trace.api.flare.TracerFlare;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.DDSpan;
import datadog.trace.core.PendingTrace;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipOutputStream;

public final class TracerDump implements TracerFlare.Reporter {

  private static final TracerDump INSTANCE = new TracerDump();

  public static void register() {
    TracerFlare.addReporter(INSTANCE);
  }

  private static final Set<WeakReference<DDSpan>> rootSpans = new HashSet<>();
  private static final Map<String, WeakReference<DDSpan>> idToWeakRef = new HashMap<>();

  public static void addUnfinishedRootSpan(final AgentSpan rootSpan) {
    DDSpan rootDDSpan = (DDSpan) rootSpan;
    WeakReference<DDSpan> weakRootSpan = new WeakReference<>(rootDDSpan);
    idToWeakRef.put(getSpanIdString(rootSpan), weakRootSpan);
    rootSpans.add(weakRootSpan);
  }

  public static void suspendRootSpan(final AgentSpan rootSpan) {
    WeakReference<DDSpan> weakRootSpan = idToWeakRef.get(getSpanIdString(rootSpan));
    rootSpans.remove(weakRootSpan);
  }

  @Override
  public void addReportToFlare(ZipOutputStream zip) throws IOException {
    StringBuilder dumpText = new StringBuilder();
    for (WeakReference<DDSpan> weakRootSpan : rootSpans) {
      DDSpan rootSpan = weakRootSpan.get();
      if (rootSpan != null) {
        PendingTrace trace = (PendingTrace) rootSpan.context().getTraceCollector();
        if (trace != null) {
          for (DDSpan span : trace.getSpans()) {
            dumpText.append(span.toString()).append("\n");
          }
        }
      }
    }
    TracerFlare.addText(zip, "trace_dump.txt", dumpText.toString());
  }

  private static String getSpanIdString(final AgentSpan rootSpan) {
    return String.valueOf(rootSpan.getSpanId());
  }
}
