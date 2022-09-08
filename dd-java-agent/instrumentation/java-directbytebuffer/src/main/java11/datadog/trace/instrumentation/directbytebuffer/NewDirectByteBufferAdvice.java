package datadog.trace.instrumentation.directbytebuffer;

import static datadog.trace.bootstrap.instrumentation.jfr.directallocation.DirectAllocationEvent.onJNIAllocation;

import net.bytebuddy.asm.Advice;

public class NewDirectByteBufferAdvice {

  @Advice.OnMethodExit
  public static void exit(@Advice.Argument(1) int capacity) {
    onJNIAllocation(capacity);
  }
}
