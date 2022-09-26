package datadog.trace.instrumentation.directbytebuffer;

import static datadog.trace.bootstrap.instrumentation.jfr.directallocation.DirectAllocationEvent.onDirectAllocation;

import net.bytebuddy.asm.Advice;

public class AllocateDirectAdvice {
  @Advice.OnMethodExit
  public static void exit(@Advice.Argument(0) int capacity) {
    onDirectAllocation(capacity);
  }
}
