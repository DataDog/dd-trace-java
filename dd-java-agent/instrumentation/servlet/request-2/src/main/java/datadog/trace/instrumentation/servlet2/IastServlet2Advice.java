package datadog.trace.instrumentation.servlet2;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.ApplicationModule;
import datadog.trace.bootstrap.InstrumentationContext;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServlet;
import net.bytebuddy.asm.Advice;

public class IastServlet2Advice {

  @Sink(VulnerabilityTypes.APPLICATION)
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void onExit(@Advice.This Object servlet) {
    final ApplicationModule applicationModule = InstrumentationBridge.APPLICATION;
    if (applicationModule == null) {
      return;
    }
    if (!(servlet instanceof HttpServlet)) {
      return;
    }
    final ServletContext context = ((HttpServlet) servlet).getServletContext();
    if (InstrumentationContext.get(ServletContext.class, Boolean.class).get(context) != null) {
      return;
    }
    InstrumentationContext.get(ServletContext.class, Boolean.class).put(context, true);
    if (applicationModule != null) {
      applicationModule.onRealPath(context.getRealPath("/"));
    }
  }
}
