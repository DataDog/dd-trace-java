package datadog.trace.bootstrap.instrumentation.jfr.directallocation;

import datadog.trace.bootstrap.instrumentation.jfr.ContextualEvent;
import jdk.jfr.Category;
import jdk.jfr.DataAmount;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

@Name("datadog.DirectAllocationSample")
@Label("Direct Allocation")
@Description("Datadog event corresponding to a direct allocation.")
@Category("Datadog")
public class DirectAllocationSampleEvent extends Event implements ContextualEvent {

  @Label("Bytes Allocated")
  @DataAmount
  private final long allocated;

  @Label("Allocation Source")
  private final String source;

  @Label("Allocating Class")
  private final String allocatingClass;

  @Label("Local Root Span Id")
  private long localRootSpanId;

  @Label("Span Id")
  private long spanId;

  public DirectAllocationSampleEvent(String allocatingClass, String source, long allocated) {
    this.allocatingClass = allocatingClass;
    this.allocated = allocated;
    this.source = source;
    captureContext();
  }

  @Override
  public void setContext(long localRootSpanId, long spanId) {
    this.localRootSpanId = localRootSpanId;
    this.spanId = spanId;
  }
}
