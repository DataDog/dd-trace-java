package datadog.trace.instrumentation.servlet.request;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.IastCallSites.Propagation;
import datadog.trace.api.iast.IastCallSites.Sink;
import datadog.trace.api.iast.IastCallSites.Source;
import datadog.trace.api.iast.InstrumentationBridge;
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
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;

@CallSite(spi = IastCallSites.class)
public class ServletRequestCallSite {

  @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
  @CallSite.After("java.lang.String javax.servlet.ServletRequest.getParameter(java.lang.String)")
  @CallSite.After(
      "java.lang.String javax.servlet.http.HttpServletRequest.getParameter(java.lang.String)")
  @CallSite.After(
      "java.lang.String javax.servlet.http.HttpServletRequestWrapper.getParameter(java.lang.String)")
  @CallSite.After(
      "java.lang.String javax.servlet.ServletRequestWrapper.getParameter(java.lang.String)")
  public static String afterGetParameter(
      @CallSite.This final ServletRequest self,
      @CallSite.Argument final String paramName,
      @CallSite.Return final String paramValue) {
    final WebModule module = InstrumentationBridge.WEB;
    if (module != null) {
      try {
        module.onParameterValue(paramName, paramValue);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterGetParameter threw", e);
      }
    }
    return paramValue;
  }

  @Source(SourceTypes.REQUEST_PARAMETER_NAME)
  @CallSite.After("java.util.Enumeration javax.servlet.ServletRequest.getParameterNames()")
  @CallSite.After("java.util.Enumeration javax.servlet.http.HttpServletRequest.getParameterNames()")
  @CallSite.After(
      "java.util.Enumeration javax.servlet.http.HttpServletRequestWrapper.getParameterNames()")
  @CallSite.After("java.util.Enumeration javax.servlet.ServletRequestWrapper.getParameterNames()")
  public static Enumeration<String> afterGetParameterNames(
      @CallSite.This final ServletRequest self,
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
      "java.lang.String[] javax.servlet.ServletRequest.getParameterValues(java.lang.String)")
  @CallSite.After(
      "java.lang.String[] javax.servlet.http.HttpServletRequest.getParameterValues(java.lang.String)")
  @CallSite.After(
      "java.lang.String[] javax.servlet.http.HttpServletRequestWrapper.getParameterValues(java.lang.String)")
  @CallSite.After(
      "java.lang.String[] javax.servlet.ServletRequestWrapper.getParameterValues(java.lang.String)")
  public static String[] afterGetParameterValues(
      @CallSite.This final ServletRequest self,
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

  @Propagation
  @CallSite.After("javax.servlet.ServletInputStream javax.servlet.ServletRequest.getInputStream()")
  @CallSite.After(
      "javax.servlet.ServletInputStream javax.servlet.http.HttpServletRequest.getInputStream()")
  @CallSite.After(
      "javax.servlet.ServletInputStream javax.servlet.http.HttpServletRequestWrapper.getInputStream()")
  @CallSite.After(
      "javax.servlet.ServletInputStream javax.servlet.ServletRequestWrapper.getInputStream()")
  public static ServletInputStream afterGetInputStream(
      @CallSite.This final ServletRequest self,
      @CallSite.Return final ServletInputStream inputStream) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null) {
      try {
        module.taint(SourceTypes.REQUEST_BODY, inputStream);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterGetInputStream threw", e);
      }
    }
    return inputStream;
  }

  @Propagation
  @CallSite.After("java.io.BufferedReader javax.servlet.ServletRequest.getReader()")
  @CallSite.After("java.io.BufferedReader javax.servlet.http.HttpServletRequest.getReader()")
  @CallSite.After("java.io.BufferedReader javax.servlet.http.HttpServletRequestWrapper.getReader()")
  @CallSite.After("java.io.BufferedReader javax.servlet.ServletRequestWrapper.getReader()")
  public static BufferedReader afterGetReader(
      @CallSite.This final ServletRequest self,
      @CallSite.Return final BufferedReader bufferedReader) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null) {
      try {
        module.taint(SourceTypes.REQUEST_BODY, bufferedReader);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterGetReader threw", e);
      }
    }
    return bufferedReader;
  }

  @Sink(VulnerabilityTypes.UNVALIDATED_REDIRECT)
  @CallSite.Before(
      "javax.servlet.RequestDispatcher javax.servlet.ServletRequest.getRequestDispatcher(java.lang.String)")
  @CallSite.Before(
      "javax.servlet.RequestDispatcher javax.servlet.http.HttpServletRequest.getRequestDispatcher(java.lang.String)")
  @CallSite.Before(
      "javax.servlet.RequestDispatcher javax.servlet.http.HttpServletRequestWrapper.getRequestDispatcher(java.lang.String)")
  @CallSite.Before(
      "javax.servlet.RequestDispatcher javax.servlet.ServletRequestWrapper.getRequestDispatcher(java.lang.String)")
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
