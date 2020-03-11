package datadog.exceptions.instrumentation;

import com.datadog.profiling.exceptions.ExceptionProfiling;
import com.datadog.profiling.exceptions.ExceptionSampleEvent;
import net.bytebuddy.asm.Advice;

public class ExceptionAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void onExit(@Advice.This final Exception e) {
    /*
     * JFR will assign the stacktrace depending on the place where the event is committed.
     * Therefore we need to commit the event here, right in the 'Exception' constructor
     */
    final ExceptionSampleEvent event = ExceptionProfiling.getInstance().process(e);
    if (event != null && event.shouldCommit()) {
      event.commit();
    }
  }
}
