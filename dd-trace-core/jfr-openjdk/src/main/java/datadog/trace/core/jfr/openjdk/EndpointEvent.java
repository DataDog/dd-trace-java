package datadog.trace.core.jfr.openjdk;

import datadog.trace.core.DDSpan;
import datadog.trace.core.EndpointTracker;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name("datadog.Endpoint")
@Label("Endpoint")
@Description("Datadog event corresponding to the endpoint of a trace root.")
@Category("Datadog")
@StackTrace(false)
public class EndpointEvent extends Event implements EndpointTracker {

  @Label("Endpoint")
  private String endpoint = "unknown";

  @Label("Trace Id")
  private final long traceId;

  @Label("Local Root Span Id")
  private final long localRootSpanId;

  /**
   * Set to {@literal true} if the corresponding trace and checkpoints was kept by agent side
   * sampler(s)
   */
  @Label("Trace Sampled")
  private boolean traceSampled = false;

  @Label("Trace Eligible for Dropping")
  private boolean eligibleForDropping;

  @Label("Checkpoints Sampled")
  private boolean checkpointsSampled = false;

  public EndpointEvent(final DDSpan span) {
    this.traceId = span.getTraceId().toLong();
    this.localRootSpanId = span.getSpanId().toLong();
    begin();
  }

  @Override
  public void endpointWritten(DDSpan span, boolean traceSampled, boolean checkpointsSampled) {
    if (shouldCommit()) {
      end();
      this.endpoint = span.getResourceName().toString();
      this.eligibleForDropping = span.eligibleForDropping();
      this.traceSampled = traceSampled;
      this.checkpointsSampled = checkpointsSampled;
      commit();
    }
  }
}
