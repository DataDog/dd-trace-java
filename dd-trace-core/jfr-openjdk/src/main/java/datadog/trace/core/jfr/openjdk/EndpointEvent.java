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

  @Label("Route")
  private final String endpoint;

  @Label("Trace Id")
  private final long traceId;

  public EndpointEvent(final String endpoint, final long traceId) {
    this.endpoint = endpoint;
    this.traceId = traceId;
  }
}
