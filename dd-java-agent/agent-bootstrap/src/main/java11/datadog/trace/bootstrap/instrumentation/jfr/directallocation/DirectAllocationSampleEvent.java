package datadog.trace.bootstrap.instrumentation.jfr.directallocation;

import datadog.trace.bootstrap.instrumentation.jfr.ContextualEvent;
import jdk.jfr.Category;
import jdk.jfr.DataAmount;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;

@Name("datadog.DirectAllocationSample")
@Label("Direct Allocation")
@Description("Datadog event corresponding to a direct allocation.")
@Category("Datadog")
public class DirectAllocationSampleEvent extends ContextualEvent {

  @Label("Bytes Allocated")
  @DataAmount
  private final long allocated;

  @Label("Allocation Source")
  private final String source;

  @Label("Allocating Class")
  private final String allocatingClass;

  public DirectAllocationSampleEvent(String allocatingClass, String source, long allocated) {
    this.allocatingClass = allocatingClass;
    this.allocated = allocated;
    this.source = source;
  }
}
