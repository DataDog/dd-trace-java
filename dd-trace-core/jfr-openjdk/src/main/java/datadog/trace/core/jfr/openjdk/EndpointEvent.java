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

  /**
   * Set to {@literal true} if the corresponding trace was decided to be kept by agent side
   * sampler(s)
   */
  @Label("Sampled")
  private final boolean sampled;

  public EndpointEvent(final String endpoint, final long traceId, final boolean sampled) {
    this.endpoint = endpoint;
    this.traceId = traceId;
    this.sampled = sampled;
  }
}
