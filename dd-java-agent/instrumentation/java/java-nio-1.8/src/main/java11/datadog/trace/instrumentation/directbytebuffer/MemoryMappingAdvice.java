package datadog.trace.instrumentation.directbytebuffer;

import static datadog.trace.bootstrap.instrumentation.jfr.directallocation.DirectAllocationSource.MMAP;

import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.jfr.InstrumentationBasedProfiling;
import datadog.trace.bootstrap.instrumentation.jfr.directallocation.DirectAllocationProfiling;
import datadog.trace.bootstrap.instrumentation.jfr.directallocation.DirectAllocationSampleEvent;
import net.bytebuddy.asm.Advice;

public class MemoryMappingAdvice {

  @Advice.OnMethodEnter
  public static DirectAllocationSampleEvent enter(@Advice.Argument(2) long capacity) {
    // reporting or sampling may lead to direct allocation so we need to track the depth
    int callDepth = CallDepthThreadLocalMap.incrementCallDepth(DirectAllocationProfiling.class);
    if (callDepth == 0 && InstrumentationBasedProfiling.isJFRReady()) {
      Class<?> caller = DirectAllocationProfiling.getInstance().getStackWalker().getCallerClass();
      return DirectAllocationProfiling.getInstance().sample(MMAP, caller, capacity);
    }
    return null;
  }

  @Advice.OnMethodExit
  public static void exit(@Advice.Enter DirectAllocationSampleEvent sample) {
    if (sample != null) {
      sample.end();
      if (sample.shouldCommit()) {
        sample.commit();
      }
    }
    CallDepthThreadLocalMap.decrementCallDepth(DirectAllocationProfiling.class);
  }
}
