package datadog.trace.core.jfr.openjdk;

import datadog.trace.api.DDId;
import datadog.trace.core.util.SystemAccess;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;

@Name("datadog.Scope")
@Label("Scope")
@Description("Datadog event corresponding to a scope.")
@Category("Datadog")
@StackTrace(false)
public final class ScopeEvent extends Event {
  @Label("Trace Id")
  private final long traceId;

  @Label("Span Id")
  private final long spanId;

  @Label("Thread CPU Time")
  @Timespan
  // does not need to be volatile since the event is created and committed from the same thread
  private long cpuTime = 0L;

  ScopeEvent(DDId traceId, DDId spanId) {
    this.traceId = traceId.toLong();
    this.spanId = spanId.toLong();
  }

  public void start() {
    if (isEnabled()) {
      cpuTime = SystemAccess.getCurrentThreadCpuTime();
      begin();
    }
  }

  public void finish() {
    end();
    if (shouldCommit()) {
      if (cpuTime > 0) {
        cpuTime = SystemAccess.getCurrentThreadCpuTime() - cpuTime;
      }
      commit();
    }
  }
}
