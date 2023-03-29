package datadog.trace.instrumentation.servlet.request;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import javax.servlet.http.Cookie;
import net.bytebuddy.asm.Advice;

public class GetCookiesAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void getCookies(@Advice.Return Cookie[] result) {
    if (result == null) {
      return;
    }
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null) {
      module.taint(SourceTypes.REQUEST_COOKIE_VALUE, (Object[]) result);
    }
  }
}
