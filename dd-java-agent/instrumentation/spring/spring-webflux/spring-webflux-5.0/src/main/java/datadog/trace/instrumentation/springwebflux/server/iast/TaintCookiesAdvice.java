package datadog.trace.instrumentation.springwebflux.server.iast;

import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.IastContext;
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
  public static void after(
      @Advice.Return MultiValueMap<String, HttpCookie> cookies,
      @ActiveRequestContext RequestContext reqCtx) {
    PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module == null || cookies.isEmpty()) {
      return;
    }

    final IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
    for (List<HttpCookie> cookieList : cookies.values()) {
      for (HttpCookie cookie : cookieList) {
        final String name = cookie.getName();
        final String value = cookie.getValue();
        module.taintString(ctx, name, SourceTypes.REQUEST_COOKIE_NAME, name);
        module.taintString(ctx, value, SourceTypes.REQUEST_COOKIE_VALUE, name);
      }
    }
  }
}
