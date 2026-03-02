package datadog.trace.instrumentation.java.lang.jdk22;


import datadog.trace.bootstrap.InstrumentationContext;
import net.bytebuddy.asm.Advice;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

public class DownCallWrapAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void onExit(@Advice.Argument(0) final MemorySegment memorySegment, @Advice.Return(readOnly = false)MethodHandle handle) {
    if (memorySegment == null || !Boolean.TRUE.equals(InstrumentationContext.get(MemorySegment.class, Boolean.class).get(memorySegment))) {
      return;
    }
    MethodHandles.
  }
}
