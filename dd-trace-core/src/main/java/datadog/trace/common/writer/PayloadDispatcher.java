package datadog.trace.common.writer;

import datadog.trace.core.CoreSpan;
import java.util.Collection;
import java.util.List;

interface PayloadDispatcher {
  void onDroppedTrace(int spanCount);

  void addTrace(List<? extends CoreSpan<?>> trace);

  void flush();

  // used by tests
  Collection<RemoteApi> getApis();
}
