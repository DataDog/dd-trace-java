package datadog.trace.instrumentation.tomcat7;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.sink.StacktraceLeakModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.IOException;
import java.io.Writer;
import net.bytebuddy.asm.Advice;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;

public class ErrorReportValueAdvice {

  @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
  public static boolean onEnter(
      @Advice.Argument(value = 0) Request request,
      @Advice.Argument(value = 1) Response response,
      @Advice.Argument(value = 2) Throwable throwable) {
    System.out.println(
        ">>> ErrorReportValue.report(" + throwable + ") -> " + response.getBytesWritten(false));

    int statusCode = response.getStatus();

    // Do nothing on a 1xx, 2xx and 3xx status
    // Do nothing if anything has been written already
    // Do nothing if the response hasn't been explicitly marked as in error
    //    and that error has not been reported.
    if (statusCode < 400 || response.getContentWritten() > 0 || !response.isError()) {
      return true; // skip original method
    }

    final AgentSpan span = activeSpan();
    if (span != null && throwable != null) {
      // Report IAST
      final StacktraceLeakModule module = InstrumentationBridge.STACKTRACE_LEAK_MODULE;
      if (module != null) {
        try {
          module.onStacktraceLeak(throwable);
        } catch (final Throwable e) {
          module.onUnexpectedException("onResponseException threw", e);
        }
      }
    }

    StringBuilder sb = new StringBuilder();
    sb.append("This is fake page displayed to suppress stack trace leak");

    try {
      try {
        response.setContentType("text/html");
        response.setCharacterEncoding("utf-8");
      } catch (Throwable t) {
        // Ignore
      }
      Writer writer = response.getReporter();
      if (writer != null) {
        // If writer is null, it's an indication that the response has
        // been hard committed already, which should never happen
        writer.write(sb.toString());
        response.finishResponse();
      }
    } catch (IOException | IllegalStateException e) {
      // Ignore
    }

    return false;
  }

  //    @Advice.OnMethodExit
  //    public static void onExit(
  //            @Advice.Argument(value = 0) Request request,
  //            @Advice.Argument(value = 1) Response response,
  //            @Advice.Argument(value = 2) Throwable throwable) {
  //        System.out.println("<<< ErrorReportValue.report(" + throwable + ") -> " +
  // response.getBytesWritten(false));
  //    }
}
