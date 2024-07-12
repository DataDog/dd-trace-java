package datadog.trace.instrumentation.servlet3;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.ApplicationModule;
import datadog.trace.bootstrap.InstrumentationContext;
import javax.servlet.ServletContext;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;
import net.bytebuddy.asm.Advice;

public class IastServlet3Advice {

  @Sink(VulnerabilityTypes.APPLICATION)
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void onExit(@Advice.Argument(0) ServletRequest request) {
    final ApplicationModule applicationModule = InstrumentationBridge.APPLICATION;
    if (applicationModule == null) {
      return;
    }
    if (!(request instanceof HttpServletRequest)) {
      return;
    }
    final ServletContext context = request.getServletContext();
    if (InstrumentationContext.get(ServletContext.class, Boolean.class).get(context) != null) {
      return;
    }
    InstrumentationContext.get(ServletContext.class, Boolean.class).put(context, true);
    if (applicationModule != null) {
      applicationModule.onRealPath(context.getRealPath("/"));
    }
  }
}
