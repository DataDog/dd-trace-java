package datadog.exceptions.instrumentation;

import static datadog.trace.util.AgentThreadFactory.AGENT_THREAD_GROUP;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.jfr.InstrumentationBasedProfiling;
import datadog.trace.bootstrap.instrumentation.jfr.exceptions.ExceptionProfiling;
import datadog.trace.bootstrap.instrumentation.jfr.exceptions.ExceptionSampleEvent;
import net.bytebuddy.asm.Advice;

public class ThrowableInstanceAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void onExit(
      @Advice.This final Throwable t,
      @Advice.FieldValue("stackTrace") StackTraceElement[] stackTrace) {
    if (t.getClass().getName().endsWith(".ResourceLeakDetector$TraceRecord")) {
      return;
    }
    /*
     * This instrumentation handler is sensitive to any throwables thrown from its body -
     * it will go into infinite loop of trying to handle the new throwable instance and generating
     * another instance while doing so.
     *
     * The solution is to keep a TLS flag and just skip the handler if it was invoked as a result of handling
     * a previous throwable instance (on the same thread).
     */
    final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(Throwable.class);
    if (callDepth > 0) {
      return;
    }
    try {
      /*
       * Exclude internal agent threads from exception profiling.
       */
      if (Config.get().isProfilingExcludeAgentThreads()
          && AGENT_THREAD_GROUP.equals(Thread.currentThread().getThreadGroup())) {
        return;
      }
      /*
       * We may get into a situation when this is called before exception sampling is active.
       */
      if (!InstrumentationBasedProfiling.isJFRReady()) {
        return;
      }
      /*
       * JFR will assign the stacktrace depending on the place where the event is committed.
       * Therefore we need to commit the event here, right in the 'Exception' constructor
       */
      final ExceptionSampleEvent event =
          ExceptionProfiling.getInstance().process(t, stackTrace == null ? 0 : stackTrace.length);
      if (event != null && event.shouldCommit()) {
        event.commit();
      }
    } finally {
      CallDepthThreadLocalMap.reset(Throwable.class);
    }
  }
}
