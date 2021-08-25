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

  @Label("endpoint")
  private final String endpoint;

  @Label("Trace Id")
  private final String traceId1;

  /**
   * Set to {@literal true} if the corresponding trace was decided to be kept by agent side
   * sampler(s)
   */
  @Label("Kept")
  private final boolean kept;

  public EndpointEvent(final String endpoint, final long traceId, final boolean kept) {
    this.endpoint = endpoint;
    this.traceId1 = String.format("%1$32s", Long.toHexString(traceId)).replace(' ', '0');
    this.kept = kept;
  }
}
