package datadog.trace.core.jfr.openjdk;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

@Name("datadog.Route")
@Label("Route")
@Description("Datadog event corresponding to the reporting of a trace root.")
@Category("Datadog")
public class RouteEvent extends Event {

  @Label("Route")
  private final String route;

  @Label("Trace Id")
  private final long traceId;

  public RouteEvent(String route, long traceId) {
    this.route = route;
    this.traceId = traceId;
  }
}
