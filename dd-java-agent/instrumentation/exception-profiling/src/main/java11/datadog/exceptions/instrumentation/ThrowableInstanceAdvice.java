package datadog.exceptions.instrumentation;

import com.datadog.profiling.exceptions.ExceptionProfiling;
import com.datadog.profiling.exceptions.ExceptionSampleEvent;
import net.bytebuddy.asm.Advice;

public class ThrowableInstanceAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void onExit(@Advice.This final Throwable t) {
    /*
     * This instrumentation handler is sensitive to any throwables thrown from its body -
     * it will go into infinite loop of trying to handle the new throwable instance and generating
     * another instance while doing so.
     *
     * The solution is to keep a TLS flag and just skip the handler if it was invoked as a result of handling
     * a previous throwable instance (on the same thread).
     */
    if (ThrowableInstanceAdviceHelper.enterHandler()) {
      try {
        /*
         * We may get into a situation when this is called before ExceptionProfiling had a chance
         * to fully initialize. So despite the fact that this returns static singleton this may
         * return null sometimes.
         */
        if (ExceptionProfiling.getInstance() == null) {
          return;
        }
        /*
         * JFR will assign the stacktrace depending on the place where the event is committed.
         * Therefore we need to commit the event here, right in the 'Exception' constructor
         */
        final ExceptionSampleEvent event = ExceptionProfiling.getInstance().process(t);
        if (event != null && event.shouldCommit()) {
          event.commit();
        }
      } finally {
        ThrowableInstanceAdviceHelper.exitHandler();
      }
    }
  }
}
