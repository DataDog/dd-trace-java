package datadog.trace.instrumentation.directbytebuffer;

import static datadog.trace.bootstrap.instrumentation.jfr.directallocation.DirectAllocationSource.JNI;

import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.jfr.InstrumentationBasedProfiling;
import datadog.trace.bootstrap.instrumentation.jfr.directallocation.DirectAllocationProfiling;
import datadog.trace.bootstrap.instrumentation.jfr.directallocation.DirectAllocationSampleEvent;
import net.bytebuddy.asm.Advice;

public class NewDirectByteBufferAdvice {

  @Advice.OnMethodExit
  public static void exit(@Advice.Argument(1) int capacity) {
    // reporting or sampling may lead to direct allocation so we need to track the depth
    int callDepth = CallDepthThreadLocalMap.incrementCallDepth(DirectAllocationProfiling.class);
    if (callDepth == 0 && InstrumentationBasedProfiling.isJFRReady()) {
      DirectAllocationSampleEvent sample =
          DirectAllocationProfiling.getInstance().sample(JNI, capacity);
      if (sample != null) {
        if (sample.shouldCommit()) {
          sample.commit();
        }
      }
    }
    CallDepthThreadLocalMap.decrementCallDepth(DirectAllocationProfiling.class);
  }
}
