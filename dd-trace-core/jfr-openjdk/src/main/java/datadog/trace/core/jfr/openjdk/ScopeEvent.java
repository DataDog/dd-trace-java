package datadog.trace.core.jfr.openjdk;

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
  private transient long childCpuTime;
  private transient long rawCpuTime;
  private final ThreadCpuTimeProvider cpuTimeProvider;

  ScopeEvent(long traceId, long spanId, ThreadCpuTimeProvider provider) {
    this.traceId = traceId;
    this.spanId = spanId;
    this.cpuTimeProvider = provider;
  }

  void addChildCpuTime(long rawCpuTime) {
    this.childCpuTime += rawCpuTime;
  }

  /**
   * Cpu time between start and finish without subtracting time spent in child scopes.
   *
   * <p>Only valid after this event is finished and if scope events are enabled
   */
  long getRawCpuTime() {
    return rawCpuTime;
  }

  public void start() {
    if (isEnabled()) {
      cpuTimeStart = cpuTimeProvider.getThreadCpuTime();
      begin();
    }
  }

  public void finish() {
    if (cpuTimeStart > 0) {
      rawCpuTime = cpuTimeProvider.getThreadCpuTime() - cpuTimeStart;
      cpuTime = rawCpuTime - childCpuTime;
    }

    end();
    if (shouldCommit()) {
      commit();
    }
  }

  long getTraceId() {
    return traceId;
  }

  long getSpanId() {
    return spanId;
  }
}
