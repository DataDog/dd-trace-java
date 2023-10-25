package datadog.trace.instrumentation.springwebflux.server.iast;

import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.util.List;
import net.bytebuddy.asm.Advice;
import org.springframework.http.HttpCookie;
import org.springframework.util.MultiValueMap;

@RequiresRequestContext(RequestContextSlot.IAST)
class TaintCookiesAdvice {

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void after(@Advice.Return MultiValueMap<String, HttpCookie> cookies) {
    PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module == null) {
      return;
    }

    for (List<HttpCookie> cookieList : cookies.values()) {
      for (HttpCookie cookie : cookieList) {
        module.taint(SourceTypes.REQUEST_COOKIE_VALUE, cookie.getName(), cookie.getValue());
      }
    }
  }
}
