package datadog.trace.instrumentation.tomcat7;

import static datadog.trace.bootstrap.blocking.BlockingActionHelper.TemplateType.HTML;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.trace.api.Config;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.sink.StacktraceLeakModule;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import net.bytebuddy.asm.Advice;
import org.apache.catalina.connector.Response;

public class ErrorReportValueAdvice {

  @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
  public static boolean onEnter(
      @Advice.Argument(value = 1) Response response,
      @Advice.Argument(value = 2) Throwable throwable,
      @Advice.Origin("#t") String className,
      @Advice.Origin("#m") String methodName) {
    int statusCode = response.getStatus();

    // Do nothing on a 1xx, 2xx, 3xx and 404 status
    // Do nothing if the response hasn't been explicitly marked as in error
    //    and that error has not been reported.
    if (statusCode < 400 || statusCode == 404 || !response.isError()) {
      return true; // skip original method
    }

    final AgentSpan span = activeSpan();
    if (span != null && throwable != null) {
      // Report IAST
      final StacktraceLeakModule module = InstrumentationBridge.STACKTRACE_LEAK_MODULE;
      if (module != null) {
        try {
          module.onStacktraceLeak(throwable, "Tomcat 7+", className, methodName);
        } catch (final Throwable e) {
          module.onUnexpectedException("onResponseException threw", e);
        }
      }
    }

    // If we don't need to suppress stacktrace leak
    if (!Config.get().isIastStacktraceLeakSuppress()) {
      return false;
    }

    byte[] template = BlockingActionHelper.getTemplate(HTML);
    if (template == null) {
      return false;
    }

    try {
      try {
        String contentType = BlockingActionHelper.getContentType(HTML);
        response.setContentType(contentType);
      } catch (Throwable t) {
        // Ignore
      }
      Writer writer = response.getReporter();
      if (writer != null) {
        // If writer is null, it's an indication that the response has
        // been hard committed already, which should never happen
        String html = new String(template, StandardCharsets.UTF_8);
        writer.write(html);
        response.finishResponse();
      }
    } catch (IOException | IllegalStateException e) {
      // Ignore
    }

    return false;
  }
}
