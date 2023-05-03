package datadog.trace.bootstrap.instrumentation.jfr.directallocation;

import jdk.jfr.Category;
import jdk.jfr.DataAmount;
import jdk.jfr.Description;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Period;
import jdk.jfr.StackTrace;

@Name("datadog.DirectAllocationTotal")
@Label("Direct Allocation Total Allocated")
@Description("Datadog direct allocation count event.")
@Category("Datadog")
@Period(value = "endChunk")
@StackTrace(false)
@Enabled
public class DirectAllocationTotalEvent extends Event {

  @Label("Allocating Class")
  private final String allocatingClass;

  @Label("Allocation Type")
  private final String source;

  @Label("Allocated")
  @DataAmount
  private final long allocated;

  public DirectAllocationTotalEvent(String allocatingClass, String allocationType, long allocated) {
    this.allocatingClass = allocatingClass;
    this.source = allocationType;
    this.allocated = allocated;
  }
}
