package datadog.trace.instrumentation.span_origin;

import net.bytebuddy.asm.Advice;

public class ExitSpanOriginAdvice {

  @Advice.OnMethodEnter
  public static void onEnter() {
    System.out.println("ExitSpanOriginAdvice.onEnter");
    /*
        StackTraceElement[] stackTrace =
            new Exception("\"ExitSpanOriginAdvice.onEnter\" trace").getStackTrace();
        AgentSpan span = AgentTracer.get().activeScope().span();
        StackTraceElement stackTraceElement = stackTrace[0];

        span.setTag(DDTags.DD_EXIT_LOCATION_FILE, stackTraceElement.getClassName());
        span.setTag(DDTags.DD_EXIT_LOCATION_LINE, stackTraceElement.getLineNumber());
    */
    //    System.out.println("<<<<<< span.getTags() = " + span.getTags());
  }
}
