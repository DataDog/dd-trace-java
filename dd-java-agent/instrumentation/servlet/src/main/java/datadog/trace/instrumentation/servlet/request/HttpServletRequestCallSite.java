package datadog.trace.instrumentation.servlet.request;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.IastAdvice.Propagation;
import datadog.trace.api.iast.IastAdvice.Source;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
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

  @Source(SourceTypes.REQUEST_HEADER_VALUE)
  @CallSite.After(
      "java.lang.String javax.servlet.http.HttpServletRequest.getHeader(java.lang.String)")
  @CallSite.After(
      "java.lang.String javax.servlet.http.HttpServletRequestWrapper.getHeader(java.lang.String)")
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

  @Source(SourceTypes.REQUEST_HEADER_VALUE)
  @CallSite.After(
      "java.util.Enumeration javax.servlet.http.HttpServletRequest.getHeaders(java.lang.String)")
  @CallSite.After(
      "java.util.Enumeration javax.servlet.http.HttpServletRequestWrapper.getHeaders(java.lang.String)")
  public static Enumeration<String> afterGetHeaders(
      @CallSite.This final HttpServletRequest self,
      @CallSite.Argument final String headerName,
      @CallSite.Return final Enumeration<String> enumeration)
      throws Throwable {
    if (enumeration == null) {
      return null;
    }
    final WebModule module = InstrumentationBridge.WEB;
    if (module == null) {
      return enumeration;
    }
    try {
      final List<String> headerValues = new ArrayList<>();
      while (enumeration.hasMoreElements()) {
        final String headerValue = enumeration.nextElement();
        headerValues.add(headerValue);
      }
      try {
        module.onHeaderValues(headerName, headerValues);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterGetHeaders threw", e);
      }
      return Collections.enumeration(headerValues);
    } catch (final Throwable e) {
      module.onUnexpectedException("afterGetHeaders threw while iterating headers", e);
      throw StackUtils.filterFirstDatadog(e);
    }
  }

  @Source(SourceTypes.REQUEST_HEADER_NAME)
  @CallSite.After("java.util.Enumeration javax.servlet.http.HttpServletRequest.getHeaderNames()")
  @CallSite.After(
      "java.util.Enumeration javax.servlet.http.HttpServletRequestWrapper.getHeaderNames()")
  public static Enumeration<String> afterGetHeaderNames(
      @CallSite.This final HttpServletRequest self,
      @CallSite.Return final Enumeration<String> enumeration)
      throws Throwable {
    if (enumeration == null) {
      return null;
    }
    final WebModule module = InstrumentationBridge.WEB;
    if (module == null) {
      return enumeration;
    }
    try {
      final List<String> headerNames = new ArrayList<>();
      while (enumeration.hasMoreElements()) {
        final String headerName = enumeration.nextElement();
        headerNames.add(headerName);
      }
      try {
        module.onHeaderNames(headerNames);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterGetHeaderNames threw", e);
      }
      return Collections.enumeration(headerNames);
    } catch (final Throwable e) {
      module.onUnexpectedException("afterGetHeaderNames threw while iterating header names", e);
      throw StackUtils.filterFirstDatadog(e);
    }
  }

  @Propagation
  @CallSite.After("javax.servlet.http.Cookie[] javax.servlet.http.HttpServletRequest.getCookies()")
  @CallSite.After(
      "javax.servlet.http.Cookie[] javax.servlet.http.HttpServletRequestWrapper.getCookies()")
  public static Cookie[] afterGetCookies(
      @CallSite.This final HttpServletRequest self, @CallSite.Return final Cookie[] cookies) {
    if (null != cookies && cookies.length > 0) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        try {
          module.taint(SourceTypes.REQUEST_COOKIE_VALUE, (Object[]) cookies);
        } catch (final Throwable e) {
          module.onUnexpectedException("afterGetCookies threw", e);
        }
      }
    }
    return cookies;
  }

  @Source(SourceTypes.REQUEST_QUERY)
  @CallSite.After("java.lang.String javax.servlet.http.HttpServletRequest.getQueryString()")
  @CallSite.After("java.lang.String javax.servlet.http.HttpServletRequestWrapper.getQueryString()")
  public static String afterGetQueryString(
      @CallSite.This final HttpServletRequest self, @CallSite.Return final String queryString) {
    final WebModule module = InstrumentationBridge.WEB;
    if (module != null) {
      try {
        module.onQueryString(queryString);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterGetQueryString threw", e);
      }
    }
    return queryString;
  }
}
