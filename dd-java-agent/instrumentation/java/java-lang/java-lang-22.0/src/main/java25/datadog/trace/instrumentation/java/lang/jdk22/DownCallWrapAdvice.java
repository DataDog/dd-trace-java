package datadog.trace.instrumentation.java.lang.jdk22;

import static datadog.trace.bootstrap.instrumentation.ffm.FFMNativeMethodDecorator.wrap;

import datadog.trace.api.Pair;
import datadog.trace.bootstrap.InstrumentationContext;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import net.bytebuddy.asm.Advice;

public class DownCallWrapAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void onExit(
      @Advice.Argument(0) final MemorySegment memorySegment,
      @Advice.Return(readOnly = false) MethodHandle handle) {
    if (memorySegment == null) {
      return;
    }
    final Pair<String, String> libAndMethod =
        InstrumentationContext.get(MemorySegment.class, Pair.class).get(memorySegment);
    if (libAndMethod == null) {
      return;
    }
    handle = wrap(handle, libAndMethod.getLeft(), libAndMethod.getRight());
  }
}
