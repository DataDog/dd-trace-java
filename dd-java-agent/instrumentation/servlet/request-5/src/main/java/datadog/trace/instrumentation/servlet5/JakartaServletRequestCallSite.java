package datadog.trace.instrumentation.servlet5;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.util.stacktrace.StackUtils;
import jakarta.servlet.ServletRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

@CallSite(spi = IastAdvice.class)
public class JakartaServletRequestCallSite {

  @CallSite.AfterArray({
    @CallSite.After(
        "java.lang.String jakarta.servlet.ServletRequest.getParameter(java.lang.String)"),
    @CallSite.After(
        "java.lang.String jakarta.servlet.http.HttpServletRequest.getParameter(java.lang.String)"),
    @CallSite.After(
        "java.lang.String jakarta.servlet.http.HttpServletRequestWrapper.getParameter(java.lang.String)"),
    @CallSite.After(
        "java.lang.String jakarta.servlet.ServletRequestWrapper.getParameter(java.lang.String)")
  })
  public static String afterGetParameter(
      @CallSite.This final ServletRequest self,
      @CallSite.Argument final String paramName,
      @CallSite.Return final String paramValue) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null) {
      try {
        module.namedTaint(SourceTypes.REQUEST_PARAMETER_VALUE, paramName, paramValue);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterGetParameter threw", e);
      }
    }
    return paramValue;
  }

  @CallSite.AfterArray({
    @CallSite.After("java.util.Enumeration jakarta.servlet.ServletRequest.getParameterNames()"),
    @CallSite.After(
        "java.util.Enumeration jakarta.servlet.http.HttpServletRequest.getParameterNames()"),
    @CallSite.After(
        "java.util.Enumeration jakarta.servlet.http.HttpServletRequestWrapper.getParameterNames()"),
    @CallSite.After(
        "java.util.Enumeration jakarta.servlet.ServletRequestWrapper.getParameterNames()")
  })
  public static Enumeration<String> afterGetParameterNames(
      @CallSite.This final ServletRequest self,
      @CallSite.Return final Enumeration<String> enumeration)
      throws Throwable {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
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
        module.taintNames(SourceTypes.REQUEST_PARAMETER_NAME, parameterNames);
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

  @CallSite.AfterArray({
    @CallSite.After(
        "java.lang.String[] jakarta.servlet.ServletRequest.getParameterValues(java.lang.String)"),
    @CallSite.After(
        "java.lang.String[] jakarta.servlet.http.HttpServletRequest.getParameterValues(java.lang.String)"),
    @CallSite.After(
        "java.lang.String[] jakarta.servlet.http.HttpServletRequestWrapper.getParameterValues(java.lang.String)"),
    @CallSite.After(
        "java.lang.String[] jakarta.servlet.ServletRequestWrapper.getParameterValues(java.lang.String)")
  })
  public static String[] afterGetParameterValues(
      @CallSite.This final ServletRequest self,
      @CallSite.Argument final String paramName,
      @CallSite.Return final String[] parameterValues) {
    if (null != parameterValues) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        try {
          module.namedTaint(SourceTypes.REQUEST_PARAMETER_VALUE, paramName, parameterValues);
        } catch (final Throwable e) {
          module.onUnexpectedException("afterGetParameterValues threw", e);
        }
      }
    }
    return parameterValues;
  }

  @CallSite.AfterArray({
    @CallSite.After("java.util.Map jakarta.servlet.ServletRequest.getParameterMap()"),
    @CallSite.After("java.util.Map jakarta.servlet.http.HttpServletRequest.getParameterMap()"),
    @CallSite.After(
        "java.util.Map jakarta.servlet.http.HttpServletRequestWrapper.getParameterMap()"),
    @CallSite.After("java.util.Map jakarta.servlet.ServletRequestWrapper.getParameterMap()")
  })
  public static java.util.Map<java.lang.String, java.lang.String[]> afterGetParameterMap(
      @CallSite.This final ServletRequest self, @CallSite.Return final Map<String, String[]> map) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null) {
      try {
        module.taintNameValuesMap(SourceTypes.REQUEST_PARAMETER_VALUE, map);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterGetParameter threw", e);
      }
    }
    return map;
  }
}
