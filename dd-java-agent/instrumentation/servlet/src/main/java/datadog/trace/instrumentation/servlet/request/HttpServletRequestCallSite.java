package datadog.trace.instrumentation.servlet.request;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.source.WebModule;
import datadog.trace.util.stacktrace.StackUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

@CallSite(spi = IastAdvice.class)
public class HttpServletRequestCallSite {

  @CallSite.AfterArray({
    @CallSite.After(
        "java.lang.String javax.servlet.http.HttpServletRequest.getHeader(java.lang.String)"),
    @CallSite.After(
        "java.lang.String javax.servlet.http.HttpServletRequestWrapper.getHeader(java.lang.String)"),
  })
  public static String afterGetHeader(
      @CallSite.This final HttpServletRequest self,
      @CallSite.Argument final String headerName,
      @CallSite.Return final String headerValue) {
    final WebModule module = InstrumentationBridge.WEB;
    if (module != null) {
      try {
        module.onHeaderValue(headerName, headerValue);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterGetHeader threw", e);
      }
    }
    return headerValue;
  }

  @CallSite.AfterArray({
    @CallSite.After(
        "java.util.Enumeration javax.servlet.http.HttpServletRequest.getHeaders(java.lang.String)"),
    @CallSite.After(
        "java.util.Enumeration javax.servlet.http.HttpServletRequestWrapper.getHeaders(java.lang.String)"),
  })
  public static Enumeration<?> afterGetHeaders(
      @CallSite.This final HttpServletRequest self,
      @CallSite.Argument final String headerName,
      @CallSite.Return final Enumeration<?> enumeration)
      throws Throwable {
    if (enumeration == null) {
      return null;
    }
    final WebModule module = InstrumentationBridge.WEB;
    if (module == null) {
      return enumeration;
    }
    try {
      final List<Object> headerValues = new ArrayList<>();
      while (enumeration.hasMoreElements()) {
        final Object headerValue = enumeration.nextElement();
        headerValues.add(headerValue);
        try {
          module.onHeaderValue(headerName, (String) headerValue);
        } catch (final Throwable e) {
          module.onUnexpectedException("afterGetHeaders threw", e);
        }
      }
      return Collections.enumeration(headerValues);
    } catch (final Throwable e) {
      module.onUnexpectedException("afterGetHeaders threw while iterating headers", e);
      throw StackUtils.filterFirstDatadog(e);
    }
  }

  @CallSite.AfterArray({
    @CallSite.After("java.util.Enumeration javax.servlet.http.HttpServletRequest.getHeaderNames()"),
    @CallSite.After(
        "java.util.Enumeration javax.servlet.http.HttpServletRequestWrapper.getHeaderNames()"),
  })
  public static Enumeration<?> afterGetHeaderNames(
      @CallSite.This final HttpServletRequest self,
      @CallSite.Return final Enumeration<?> enumeration)
      throws Throwable {
    if (enumeration == null) {
      return null;
    }
    final WebModule module = InstrumentationBridge.WEB;
    if (module == null) {
      return enumeration;
    }
    try {
      final List<Object> headerNames = new ArrayList<>();
      while (enumeration.hasMoreElements()) {
        final Object headerName = enumeration.nextElement();
        headerNames.add(headerName);
        try {
          module.onHeaderName((String) headerName);
        } catch (final Throwable e) {
          module.onUnexpectedException("afterGetHeaderNames threw", e);
        }
      }
      return Collections.enumeration(headerNames);
    } catch (final Throwable e) {
      module.onUnexpectedException("afterGetHeaderNames threw while iterating header names", e);
      throw StackUtils.filterFirstDatadog(e);
    }
  }

  @CallSite.AfterArray({
    @CallSite.After(
        "javax.servlet.http.Cookie[] javax.servlet.http.HttpServletRequest.getCookies()"),
    @CallSite.After(
        "javax.servlet.http.Cookie[] javax.servlet.http.HttpServletRequestWrapper.getCookies()")
  })
  public static Cookie[] afterGetCookies(
      @CallSite.This final HttpServletRequest self, @CallSite.Return final Cookie[] cookies) {
    if (null != cookies && cookies.length > 0) {
      final WebModule module = InstrumentationBridge.WEB;
      if (module != null) {
        try {
          module.onCookies(cookies);
        } catch (final Throwable e) {
          module.onUnexpectedException("afterGetCookies threw", e);
        }
      }
    }
    return cookies;
  }
}
