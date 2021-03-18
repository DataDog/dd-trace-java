package datadog.trace.core.jfr.oracle;

import com.oracle.jrockit.jfr.ContentType;
import com.oracle.jrockit.jfr.EventDefinition;
import com.oracle.jrockit.jfr.EventToken;
import com.oracle.jrockit.jfr.TimedEvent;
import com.oracle.jrockit.jfr.ValueDefinition;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.jfr.DDScopeEvent;
import datadog.trace.core.util.SystemAccess;

@EventDefinition(
    path = "datadog/Scope",
    name = "Scope",
    description = "Datadog event corresponding to a scope.",
    stacktrace = false,
    thread = true)
public final class ScopeEvent extends TimedEvent implements DDScopeEvent {

  private static final int IDS_RADIX = 16;

  private final transient DDSpanContext spanContext;

  @ValueDefinition(name = "Trace Id")
  long traceId;

  @ValueDefinition(name = "Span Id")
  long spanId;

  @ValueDefinition(name = "Thread CPU Time", contentType = ContentType.Nanos)
  // does not need to be volatile since the event is created and committed from the same thread
  long cpuTime = 0L;

  ScopeEvent(EventToken eventToken, final DDSpanContext spanContext) {
    super(eventToken);
    this.spanContext = spanContext;
  }

  @Override
  public void start() {
    if (getEventInfo().isEnabled()) {
      cpuTime = SystemAccess.getCurrentThreadCpuTime();
      begin();
    }
  }

  @Override
  public void finish() {
    end();
    if (shouldWrite()) {
      if (cpuTime > 0) {
        cpuTime = SystemAccess.getCurrentThreadCpuTime() - cpuTime;
      }
      traceId = spanContext.getTraceId().toLong();
      spanId = spanContext.getSpanId().toLong();
      commit();
    }
  }

  public long getCpuTime() {
    return cpuTime;
  }

  public long getTraceId() {
    return traceId;
  }

  public long getSpanId() {
    return spanId;
  }
}
