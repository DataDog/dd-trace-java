package datadog.trace.core.jfr.openjdk;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

@Name("datadog.Endpoint")
@Label("Endpoint")
@Description("Datadog event corresponding to the endpoint of a trace root.")
@Category("Datadog")
public class EndpointEvent extends Event {

  @Label("Endpoint")
  private final String endpoint;

  @Label("Trace Id")
  private final long traceId;

  @Label("Local Root Span Id")
  private final long localRootSpanId;

  /**
   * Set to {@literal true} if the corresponding trace and checkpoints was kept by agent side
   * sampler(s)
   */
  @Label("Trace Sampled")
  private final boolean traceSampled;

  @Label("Checkpoints Sampled")
  private final boolean checkpointsSampled;

  public EndpointEvent(
      final String endpoint,
      final long traceId,
      final long localRootSpanId,
      final boolean traceSampled,
      final boolean checkpointsSampled) {
    this.endpoint = endpoint;
    this.traceId = traceId;
    this.localRootSpanId = localRootSpanId;
    this.traceSampled = traceSampled;
    this.checkpointsSampled = checkpointsSampled;
  }
}
