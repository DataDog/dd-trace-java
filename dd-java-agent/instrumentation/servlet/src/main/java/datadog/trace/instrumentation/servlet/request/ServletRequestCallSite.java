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
import javax.servlet.ServletRequest;

@CallSite(spi = IastAdvice.class)
public class ServletRequestCallSite {

  @CallSite.AfterArray({
    @CallSite.After("java.lang.String javax.servlet.ServletRequest.getParameter(java.lang.String)"),
    @CallSite.After(
        "java.lang.String javax.servlet.http.HttpServletRequest.getParameter(java.lang.String)"),
    @CallSite.After(
        "java.lang.String javax.servlet.http.HttpServletRequestWrapper.getParameter(java.lang.String)"),
    @CallSite.After(
        "java.lang.String javax.servlet.ServletRequestWrapper.getParameter(java.lang.String)")
  })
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

  @CallSite.AfterArray({
    @CallSite.After("java.util.Enumeration javax.servlet.ServletRequest.getParameterNames()"),
    @CallSite.After(
        "java.util.Enumeration javax.servlet.http.HttpServletRequest.getParameterNames()"),
    @CallSite.After(
        "java.util.Enumeration javax.servlet.http.HttpServletRequestWrapper.getParameterNames()"),
    @CallSite.After("java.util.Enumeration javax.servlet.ServletRequestWrapper.getParameterNames()")
  })
  public static Enumeration<?> afterGetParameterNames(
      @CallSite.This final ServletRequest self, @CallSite.Return final Enumeration<?> enumeration)
      throws Throwable {
    final WebModule module = InstrumentationBridge.WEB;
    if (module == null) {
      return enumeration;
    }
    try {
      final List<Object> parameterNames = new ArrayList<>();
      while (enumeration.hasMoreElements()) {
        final Object paramName = enumeration.nextElement();
        parameterNames.add(paramName);
        try {
          module.onParameterName((String) paramName);
        } catch (final Throwable e) {
          module.onUnexpectedException("afterGetParameterNames threw", e);
        }
      }
      return Collections.enumeration(parameterNames);
    } catch (final Throwable e) {
      module.onUnexpectedException(
          "afterGetParameterNames threw while iterating parameter names", e);
      throw StackUtils.filterFirstDatadog(e);
    }
  }

  @CallSite.AfterArray({
    @CallSite.After(
        "java.lang.String[] javax.servlet.ServletRequest.getParameterValues(java.lang.String)"),
    @CallSite.After(
        "java.lang.String[] javax.servlet.http.HttpServletRequest.getParameterValues(java.lang.String)"),
    @CallSite.After(
        "java.lang.String[] javax.servlet.http.HttpServletRequestWrapper.getParameterValues(java.lang.String)"),
    @CallSite.After(
        "java.lang.String[] javax.servlet.ServletRequestWrapper.getParameterValues(java.lang.String)")
  })
  public static String[] afterGetParameterValues(
      @CallSite.This final ServletRequest self,
      @CallSite.Argument final String paramName,
      @CallSite.Return final String[] parameterValues) {
    if (null != parameterValues) {
      final WebModule module = InstrumentationBridge.WEB;
      if (module != null) {
        for (String paramValue : parameterValues) {
          try {
            module.onParameterValue(paramName, paramValue);
          } catch (final Throwable e) {
            module.onUnexpectedException("afterGetParameterValues threw", e);
          }
        }
      }
    }
    return parameterValues;
  }
}
