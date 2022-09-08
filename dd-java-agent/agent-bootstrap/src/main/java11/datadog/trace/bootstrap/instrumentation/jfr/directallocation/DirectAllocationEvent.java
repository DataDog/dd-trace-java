package datadog.trace.bootstrap.instrumentation.jfr.directallocation;

import jdk.jfr.Category;
import jdk.jfr.DataAmount;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

@Name("datadog.DirectAllocationEvent")
@Label("Direct Allocation")
@Description("Datadog event corresponding to a direct allocation.")
@Category("Datadog")
public class DirectAllocationEvent extends Event {

  private static final String ALLOCATE_DIRECT = "direct";
  private static final String MMAP = "mmap";
  private static final String JNI = "JNI";

  public static void onDirectAllocation(int capacity) {
    onAllocation(capacity, ALLOCATE_DIRECT);
  }

  public static void onJNIAllocation(int capacity) {
    onAllocation(capacity, JNI);
  }

  public static void onMemoryMapping(int capacity) {
    onAllocation(capacity, MMAP);
  }

  private static void onAllocation(int capacity, String source) {
    DirectAllocationEvent event = new DirectAllocationEvent(capacity, source);
    if (event.shouldCommit()) {
      event.commit();
    }
  }

  @Label("Capacity Allocated")
  @DataAmount
  private final int capacity;

  @Label("Allocation Source")
  private final String source;

  public DirectAllocationEvent(int capacity, String source) {
    this.capacity = capacity;
    this.source = source;
  }
}
