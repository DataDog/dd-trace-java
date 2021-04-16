package datadog.trace.core.jfr.openjdk;

import datadog.trace.api.SpanCorrelation;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
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
  private static final boolean COLLECT_THREAD_CPU_TIME =
      ConfigProvider.createDefault().getBoolean(ProfilingConfig.PROFILING_HOTSPTOTS_ENABLED, false);

  @Label("Trace Id")
  private long traceId;

  @Label("Span Id")
  private long spanId;

  @Label("Thread CPU Time")
  @Timespan
  // does not need to be volatile since the event is created and committed from the same thread
  private long cpuTime = Long.MIN_VALUE;

  private transient long cpuTimeStart;
  private transient long childCpuTime;
  private transient long rawCpuTime;

  ScopeEvent() {
    this(null);
  }

  ScopeEvent(SpanCorrelation correlation) {
    if (isEnabled()) {
      cpuTimeStart =
          COLLECT_THREAD_CPU_TIME ? SystemAccess.getCurrentThreadCpuTime() : Long.MIN_VALUE;
      begin();
      setCorrelation(correlation);
    }
  }

  public final void setCorrelation(SpanCorrelation correlation) {
    if (correlation != null) {
      this.traceId = correlation.getTraceId().toLong();
      this.spanId = correlation.getSpanId().toLong();
    }
  }

  public void addChildCpuTime(long rawCpuTime) {
    this.childCpuTime += rawCpuTime;
  }

  /**
   * Cpu time between start and finish without subtracting time spent in child scopes.
   *
   * <p>Only valid after this event is finished and if scope events are enabled
   */
  public long getRawCpuTime() {
    return rawCpuTime;
  }

  public void finish() {
    if (COLLECT_THREAD_CPU_TIME && cpuTimeStart > 0) {
      rawCpuTime = SystemAccess.getCurrentThreadCpuTime() - cpuTimeStart;
      cpuTime = rawCpuTime - childCpuTime;
    }

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
