package datadog.exceptions.instrumentation;

import com.datadog.profiling.exceptions.ExceptionProfiling;
import com.datadog.profiling.exceptions.ExceptionSampleEvent;
import datadog.trace.bootstrap.Agent;
import net.bytebuddy.asm.Advice;

public class ExceptionAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void onExit(@Advice.This final Exception e) {
    /*
     * Can not do this check at the instrumentation level as it is evaluated only once and way
     * too early such that profiling haven't been set up and started yet.
     * Since only one class will attempt the instrumentation (java.lang.Exception) the overhead
     * should be pretty minimal.
     */
    if (Agent.isProfilingStarted()) {
      /*
       * JFR will assign the stacktrace depending on the place where the event is committed.
       * Therefore we need to commit the event here, right in the 'Exception' constructor
       */
      ExceptionSampleEvent ese = ExceptionProfiling.getInstance().process(e);
      if (ese != null && ese.shouldCommit()) {
        ese.commit();
      }
    }
  }
}
