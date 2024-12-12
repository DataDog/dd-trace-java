package datadog.trace.api.interceptor;

public abstract class AbstractTraceInterceptor implements TraceInterceptor {

  private final Priority priority;

  protected AbstractTraceInterceptor(Priority priority) {
    this.priority = priority;
  }

  @Override
  public int priority() {
    return priority.idx;
  }

  public enum Priority {
    // trace filtering
    CI_VISIBILITY_TRACE(0),
    CI_VISIBILITY_APM(1),

    // trace data enrichment
    DD_INTAKE(2),
    GIT_METADATA(3),

    // trace custom sampling
    ROOT_SPAN_LATENCY(Integer.MAX_VALUE - 2),

    // trace data collection
    CI_VISIBILITY_TELEMETRY(Integer.MAX_VALUE - 1),
    SERVICE_NAME_COLLECTING(Integer.MAX_VALUE);

    private final int idx;

    Priority(int idx) {
      this.idx = idx;
    }

    int getIdx() {
      return idx;
    }
  }
}
