package datadog.trace.instrumentation.directbytebuffer;

import static datadog.trace.bootstrap.instrumentation.jfr.directallocation.DirectAllocationEvent.onMemoryMapping;

import net.bytebuddy.asm.Advice;

public class MemoryMappingAdvice {
  @Advice.OnMethodExit
  public static void exit(@Advice.Argument(0) int capacity) {
    onMemoryMapping(capacity);
  }
}
