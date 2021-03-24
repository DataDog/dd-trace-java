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
  private long cpuTime = Long.MIN_VALUE;

  private transient long cpuTimeStart;

  ScopeEvent(DDId traceId, DDId spanId) {
    this.traceId = traceId.toLong();
    this.spanId = spanId.toLong();

    if (isEnabled()) {
      resume();
      begin();
    }
  }

  public void pause() {
    if (cpuTimeStart > 0) {
      if (cpuTime == Long.MIN_VALUE) {
        cpuTime = SystemAccess.getCurrentThreadCpuTime() - cpuTimeStart;
      } else {
        cpuTime += SystemAccess.getCurrentThreadCpuTime() - cpuTimeStart;
      }

      cpuTimeStart = 0;
    }
  }

  public void resume() {
    cpuTimeStart = SystemAccess.getCurrentThreadCpuTime();
  }

  public void finish() {
    pause();
    end();
    if (shouldCommit()) {
      commit();
    }
  }

  public long getTraceId() {
    return traceId;
  }

  public long getSpanId() {
    return spanId;
  }
}
