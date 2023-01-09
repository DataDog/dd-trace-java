package datadog.trace.core.jfr.openjdk;

import datadog.trace.core.DDSpan;
import datadog.trace.core.EndpointTracker;
import java.util.concurrent.TimeUnit;
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

  private static final long TEN_MILLISECONDS = TimeUnit.MILLISECONDS.toNanos(10);

  @Label("Endpoint")
  private String endpoint = "unknown";

  @Label("Local Root Span Id")
  private final long localRootSpanId;

  public EndpointEvent(final DDSpan span) {
    this.localRootSpanId = span.getSpanId();
    begin();
  }

  @Override
  public void endpointWritten(DDSpan span, boolean traceSampled, boolean checkpointsSampled) {
    if (span.getDurationNano() >= TEN_MILLISECONDS && shouldCommit()) {
      end();
      this.endpoint = span.getResourceName().toString();
      commit();
    }
  }
}
