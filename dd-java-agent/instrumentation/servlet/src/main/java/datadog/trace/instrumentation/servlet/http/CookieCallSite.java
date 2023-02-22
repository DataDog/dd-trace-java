package datadog.trace.instrumentation.servlet.http;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.source.WebModule;
import javax.servlet.http.Cookie;

@CallSite(spi = IastAdvice.class)
public class CookieCallSite {

  @CallSite.After("java.lang.String javax.servlet.http.Cookie.getName()")
  public static String afterGetName(
      @CallSite.This final Cookie self, @CallSite.Return final String result) {
    final WebModule module = InstrumentationBridge.WEB;
    if (module != null) {
      try {
        module.onCookieGetter(self, self.getName(), result, (byte) 5);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterGetName threw", e);
      }
    }
    return result;
  }

  @CallSite.After("java.lang.String javax.servlet.http.Cookie.getValue()")
  public static String getValue(
      @CallSite.This final Cookie self, @CallSite.Return final String result) {
    final WebModule module = InstrumentationBridge.WEB;
    if (module != null) {
      try {
        module.onCookieGetter(self, self.getName(), result, (byte) 6);
      } catch (final Throwable e) {
        module.onUnexpectedException("getValue threw", e);
      }
    }
    return result;
  }

  @CallSite.After("java.lang.String javax.servlet.http.Cookie.getComment()")
  public static String afterGetComment(
      @CallSite.This final Cookie self, @CallSite.Return final String result) {
    final WebModule module = InstrumentationBridge.WEB;
    if (module != null) {
      try {
        module.onCookieGetter(self, self.getName(), result, (byte) 7);
      } catch (final Throwable e) {
        module.onUnexpectedException("getComment threw", e);
      }
    }
    return result;
  }

  @CallSite.After("java.lang.String javax.servlet.http.Cookie.getDomain()")
  public static String afterGetDomain(
      @CallSite.This final Cookie self, @CallSite.Return final String result) {
    final WebModule module = InstrumentationBridge.WEB;
    if (module != null) {
      try {
        module.onCookieGetter(self, self.getName(), result, (byte) 8);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterGetDomain threw", e);
      }
    }
    return result;
  }

  @CallSite.After("java.lang.String javax.servlet.http.Cookie.getPath()")
  public static String afterGetPath(
      @CallSite.This final Cookie self, @CallSite.Return final String result) {
    final WebModule module = InstrumentationBridge.WEB;
    if (module != null) {
      try {
        module.onCookieGetter(self, self.getName(), result, (byte) 9);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterGetPath threw", e);
      }
    }
    return result;
  }
}
