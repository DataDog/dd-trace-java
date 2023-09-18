package datadog.trace.instrumentation.servlet.request;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule;
import datadog.trace.api.iast.source.WebModule;
import datadog.trace.util.stacktrace.StackUtils;
import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

@CallSite(spi = IastCallSites.class)
public class HttpServletRequestCallSite {

  @Source(SourceTypes.REQUEST_HEADER_VALUE)
  @CallSite.After(
      "java.lang.String javax.servlet.http.HttpServletRequest.getHeader(java.lang.String)")
  @CallSite.After(
      "java.lang.String javax.servlet.http.HttpServletRequestWrapper.getHeader(java.lang.String)")
  public static String afterGetHeader(
      @CallSite.This final HttpServletRequest self,
      @CallSite.Argument final String name,
      @CallSite.Return final String value) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null) {
      try {
        module.taint(SourceTypes.REQUEST_HEADER_VALUE, name, value);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterGetHeader threw", e);
      }
    }
    return value;
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

  @Source(SourceTypes.REQUEST_COOKIE_VALUE)
  @CallSite.After("javax.servlet.http.Cookie[] javax.servlet.http.HttpServletRequest.getCookies()")
  @CallSite.After(
      "javax.servlet.http.Cookie[] javax.servlet.http.HttpServletRequestWrapper.getCookies()")
  public static Cookie[] afterGetCookies(
      @CallSite.This final HttpServletRequest self, @CallSite.Return final Cookie[] cookies) {
    if (null != cookies && cookies.length > 0) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        try {
          module.taintObjects(SourceTypes.REQUEST_COOKIE_VALUE, cookies);
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
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null) {
      try {
        module.taint(SourceTypes.REQUEST_QUERY, null, queryString);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterGetQueryString threw", e);
      }
    }
    return queryString;
  }

  @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
  @CallSite.After(
      "java.lang.String javax.servlet.http.HttpServletRequest.getParameter(java.lang.String)")
  @CallSite.After(
      "java.lang.String javax.servlet.http.HttpServletRequestWrapper.getParameter(java.lang.String)")
  public static String afterGetParameter(
      @CallSite.This final HttpServletRequest self,
      @CallSite.Argument final String name,
      @CallSite.Return final String value) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null) {
      try {
        module.taint(SourceTypes.REQUEST_PARAMETER_VALUE, name, value);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterGetParameter threw", e);
      }
    }
    return value;
  }

  @Source(SourceTypes.REQUEST_PARAMETER_NAME)
  @CallSite.After("java.util.Enumeration javax.servlet.http.HttpServletRequest.getParameterNames()")
  @CallSite.After(
      "java.util.Enumeration javax.servlet.http.HttpServletRequestWrapper.getParameterNames()")
  public static Enumeration<String> afterGetParameterNames(
      @CallSite.This final HttpServletRequest self,
      @CallSite.Return final Enumeration<String> enumeration)
      throws Throwable {
    final WebModule module = InstrumentationBridge.WEB;
    if (module == null) {
      return enumeration;
    }
    try {
      final List<String> parameterNames = new ArrayList<>();
      while (enumeration.hasMoreElements()) {
        final String paramName = enumeration.nextElement();
        parameterNames.add(paramName);
      }
      try {
        module.onParameterNames(parameterNames);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterGetParameterNames threw", e);
      }
      return Collections.enumeration(parameterNames);
    } catch (final Throwable e) {
      module.onUnexpectedException(
          "afterGetParameterNames threw while iterating parameter names", e);
      throw StackUtils.filterFirstDatadog(e);
    }
  }

  @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
  @CallSite.After(
      "java.lang.String[] javax.servlet.http.HttpServletRequest.getParameterValues(java.lang.String)")
  @CallSite.After(
      "java.lang.String[] javax.servlet.http.HttpServletRequestWrapper.getParameterValues(java.lang.String)")
  public static String[] afterGetParameterValues(
      @CallSite.This final HttpServletRequest self,
      @CallSite.Argument final String paramName,
      @CallSite.Return final String[] parameterValues) {
    if (null != parameterValues) {
      final WebModule module = InstrumentationBridge.WEB;
      if (module != null) {
        try {
          module.onParameterValues(paramName, parameterValues);
        } catch (final Throwable e) {
          module.onUnexpectedException("afterGetParameterValues threw", e);
        }
      }
    }
    return parameterValues;
  }

  @Source(SourceTypes.REQUEST_BODY)
  @CallSite.After("java.io.BufferedReader javax.servlet.http.HttpServletRequest.getReader()")
  @CallSite.After("java.io.BufferedReader javax.servlet.http.HttpServletRequestWrapper.getReader()")
  public static BufferedReader afterGetReader(
      @CallSite.This final HttpServletRequest self,
      @CallSite.Return final BufferedReader bufferedReader) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null) {
      try {
        module.taintObject(SourceTypes.REQUEST_BODY, bufferedReader);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterGetReader threw", e);
      }
    }
    return bufferedReader;
  }

  @Sink(VulnerabilityTypes.UNVALIDATED_REDIRECT)
  @CallSite.Before(
      "javax.servlet.RequestDispatcher javax.servlet.http.HttpServletRequest.getRequestDispatcher(java.lang.String)")
  @CallSite.Before(
      "javax.servlet.RequestDispatcher javax.servlet.http.HttpServletRequestWrapper.getRequestDispatcher(java.lang.String)")
  public static void beforeRequestDispatcher(@CallSite.Argument final String path) {
    final UnvalidatedRedirectModule module = InstrumentationBridge.UNVALIDATED_REDIRECT;
    if (module != null) {
      try {
        module.onRedirect(path);
      } catch (final Throwable e) {
        module.onUnexpectedException("beforeRequestDispatcher threw", e);
      }
    }
  }
}
