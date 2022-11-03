package datadog.trace.core.jfr.openjdk;

import datadog.trace.api.DDId;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.scopemanager.ExtendedScopeListener;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.core.util.SystemAccess;
import java.util.ArrayDeque;
import java.util.Deque;
import jdk.jfr.EventType;

/** Event factory for {@link ScopeEvent} */
public class ScopeEventFactory implements ExtendedScopeListener {
  private final ThreadCpuTimeProvider threadCpuTimeProvider =
      ConfigProvider.createDefault().getBoolean(ProfilingConfig.PROFILING_HOTSPOTS_ENABLED, false)
          ? SystemAccess::getCurrentThreadCpuTime
          : () -> Long.MIN_VALUE;

  private final ThreadLocal<Deque<ScopeEvent>> scopeEventStack =
      ThreadLocal.withInitial(ArrayDeque::new);

  public ScopeEventFactory() {
    ExcludedVersions.checkVersionExclusion();
    // Note: Loading ScopeEvent when ScopeEventFactory is loaded is important because it also loads
    // JFR classes - which may not be present on some JVMs
    EventType.getEventType(ScopeEvent.class);
  }

  @Override
  public void afterScopeActivated() {
    afterScopeActivated(DDId.ZERO, DDId.ZERO, DDId.ZERO);
  }

  @Override
  public void afterScopeActivated(DDId traceId, DDId localRootSpanId, DDId spanId) {
    Deque<ScopeEvent> stack = scopeEventStack.get();

    ScopeEvent top = stack.peek();

    long traceIdNum = traceId.toLong();
    long spanIdNum = spanId.toLong();

    if (top == null || top.getTraceId() != traceIdNum || top.getSpanId() != spanIdNum) {
      ScopeEvent event = new ScopeEvent(traceIdNum, spanIdNum, threadCpuTimeProvider);
      stack.push(event);
      event.start();
    }
  }

  @Override
  public void afterScopeClosed() {
    Deque<ScopeEvent> stack = scopeEventStack.get();

    ScopeEvent scopeEvent = stack.poll();
    if (scopeEvent != null) {
      scopeEvent.finish();

      ScopeEvent parent = stack.peek();
      if (parent != null) {
        parent.addChildCpuTime(scopeEvent.getRawCpuTime());
      }
    }
  }
}
