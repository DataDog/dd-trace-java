package datadog.trace.core.util;

import datadog.trace.api.flare.TracerFlare;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.DDSpan;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipOutputStream;

public final class TracerDump {
  private static final Map<WeakReference<DDSpan>, List<WeakReference<DDSpan>>> activeSpans =
      new HashMap<>();

  public static void addActiveSpan(final DDSpan span, boolean isRootSpan) {
    WeakReference<DDSpan> weakRootSpan =
        isRootSpan ? new WeakReference<>(span) : new WeakReference<>(span.getLocalRootSpan());
    if (isRootSpan) {
      List<WeakReference<DDSpan>> childSpans = new ArrayList<>();
      activeSpans.put(weakRootSpan, childSpans);
    }
    WeakReference<DDSpan> childSpan = new WeakReference<>(span);
    activeSpans.get(weakRootSpan).add(childSpan);
  }

  public static void suspendSpansFromRootSpan(AgentSpan rootSpan) {
    DDSpan rootDDSpan = (DDSpan) rootSpan;
    WeakReference<DDSpan> weakRootSpan = new WeakReference<>(rootDDSpan);
    activeSpans.remove(weakRootSpan);
  }

  public static void dumpTrace(ZipOutputStream zip) throws IOException {
    for (List<WeakReference<DDSpan>> spans : activeSpans.values()) {
      for (WeakReference<DDSpan> weakSpan : spans) {
        DDSpan span = weakSpan.get();
        if (span != null) {
          TracerFlare.addText(zip, "trace_dump.txt", span.toString());
        }
      }
    }
  }
}
