package datadog.trace.instrumentation.servlet.http;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.IastAdvice.Source;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.source.WebModule;
import javax.servlet.http.Cookie;

@CallSite(spi = IastAdvice.class)
public class CookieCallSite {

  @Source(SourceTypes.REQUEST_COOKIE_NAME_STRING)
  @CallSite.After("java.lang.String javax.servlet.http.Cookie.getName()")
  public static String afterGetName(
      @CallSite.This final Cookie self, @CallSite.Return final String result) {
    final WebModule module = InstrumentationBridge.WEB;
    if (module != null) {
      try {
        module.onCookieGetter(self, self.getName(), result, SourceTypes.REQUEST_COOKIE_NAME);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterGetName threw", e);
      }
    }
    return result;
  }

  @Source(SourceTypes.REQUEST_COOKIE_VALUE_STRING)
  @CallSite.After("java.lang.String javax.servlet.http.Cookie.getValue()")
  public static String getValue(
      @CallSite.This final Cookie self, @CallSite.Return final String result) {
    final WebModule module = InstrumentationBridge.WEB;
    if (module != null) {
      try {
        module.onCookieGetter(self, self.getName(), result, SourceTypes.REQUEST_COOKIE_VALUE);
      } catch (final Throwable e) {
        module.onUnexpectedException("getValue threw", e);
      }
    }
    return result;
  }
}
