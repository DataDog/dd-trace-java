package datadog.trace.instrumentation.springwebflux.server.iast;

import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.taint.TaintedObjects;
import java.util.List;
import net.bytebuddy.asm.Advice;
import org.springframework.http.HttpCookie;
import org.springframework.util.MultiValueMap;

class TaintCookiesAdvice {

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void after(@Advice.Return MultiValueMap<String, HttpCookie> cookies) {
    PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module == null || cookies.isEmpty()) {
      return;
    }

    final TaintedObjects to = IastContext.Provider.taintedObjects();
    for (List<HttpCookie> cookieList : cookies.values()) {
      for (HttpCookie cookie : cookieList) {
        final String name = cookie.getName();
        final String value = cookie.getValue();
        module.taintObject(to, name, SourceTypes.REQUEST_COOKIE_NAME, name);
        module.taintObject(to, value, SourceTypes.REQUEST_COOKIE_VALUE, name);
      }
    }
  }
}
