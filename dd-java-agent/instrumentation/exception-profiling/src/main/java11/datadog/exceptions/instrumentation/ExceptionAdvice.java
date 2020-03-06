package datadog.exceptions.instrumentation;

import datadog.trace.bootstrap.instrumentation.jfr.exceptions.ExceptionEvents;
import datadog.trace.bootstrap.instrumentation.jfr.exceptions.ExceptionSampleEvent;
import net.bytebuddy.asm.Advice;

public class ExceptionAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void onExit(@Advice.This final Exception e) {
    ExceptionSampleEvent ese = ExceptionEvents.process(e);
    if (ese != null && ese.shouldCommit()) {
      ese.commit();
    }
  }
}
