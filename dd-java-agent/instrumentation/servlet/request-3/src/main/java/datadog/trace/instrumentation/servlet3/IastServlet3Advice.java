package datadog.trace.instrumentation.servlet3;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.ApplicationModule;
import datadog.trace.api.iast.sink.SessionRewritingModule;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.HashSet;
import java.util.Set;
import javax.servlet.ServletContext;
import javax.servlet.ServletRequest;
import javax.servlet.SessionTrackingMode;
import datadog.trace.bootstrap.InstrumentationContext;
import javax.servlet.ServletContext;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequest;
import net.bytebuddy.asm.Advice;

public class IastServlet3Advice {

  @Sink(VulnerabilityTypes.APPLICATION)
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void onExit(@Advice.Argument(0) ServletRequest request) {
    final ApplicationModule applicationModule = InstrumentationBridge.APPLICATION;
    final SessionRewritingModule sessionRewritingModule = InstrumentationBridge.SESSION_REWRITING;
    if (applicationModule == null && sessionRewritingModule == null) {
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
    if (sessionRewritingModule != null
        && context.getEffectiveSessionTrackingModes() != null
        && !context.getEffectiveSessionTrackingModes().isEmpty()) {
      Set<String> sessionTrackingModes = new HashSet<>();
      for (SessionTrackingMode mode : context.getEffectiveSessionTrackingModes()) {
        sessionTrackingModes.add(mode.name());
      }
      sessionRewritingModule.checkSessionTrackingModes(sessionTrackingModes);
    }
  }
}
