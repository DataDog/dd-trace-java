package datadog.trace.instrumentation.servlet5;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule;
import datadog.trace.util.stacktrace.StackUtils;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

@CallSite(spi = IastCallSites.class)
public class JakartaHttpServletRequestCallSite {

  @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
  @CallSite.After(
      "java.lang.String jakarta.servlet.http.HttpServletRequest.getParameter(java.lang.String)")
  @CallSite.After(
      "java.lang.String jakarta.servlet.http.HttpServletRequestWrapper.getParameter(java.lang.String)")
  public static String afterGetParameter(
      @CallSite.This final HttpServletRequest self,
      @CallSite.Argument final String name,
      @CallSite.Return final String value) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null) {
      try {
        module.taint(value, SourceTypes.REQUEST_PARAMETER_VALUE, name);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterGetParameter threw", e);
      }
    }
    return value;
  }

  @Source(SourceTypes.REQUEST_PARAMETER_NAME)
  @CallSite.After(
      "java.util.Enumeration jakarta.servlet.http.HttpServletRequest.getParameterNames()")
  @CallSite.After(
      "java.util.Enumeration jakarta.servlet.http.HttpServletRequestWrapper.getParameterNames()")
  public static Enumeration<String> afterGetParameterNames(
      @CallSite.This final HttpServletRequest self,
      @CallSite.Return final Enumeration<String> enumeration)
      throws Throwable {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module == null || enumeration == null) {
      return enumeration;
    }
    try {
      final List<String> parameterNames = new ArrayList<>();
      while (enumeration.hasMoreElements()) {
        final String paramName = enumeration.nextElement();
        parameterNames.add(paramName);
      }
      try {
        final IastContext ctx = IastContext.Provider.get();
        for (final String name : parameterNames) {
          module.taint(ctx, name, SourceTypes.REQUEST_PARAMETER_NAME, name);
        }
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
      "java.lang.String[] jakarta.servlet.http.HttpServletRequest.getParameterValues(java.lang.String)")
  @CallSite.After(
      "java.lang.String[] jakarta.servlet.http.HttpServletRequestWrapper.getParameterValues(java.lang.String)")
  public static String[] afterGetParameterValues(
      @CallSite.This final HttpServletRequest self,
      @CallSite.Argument final String paramName,
      @CallSite.Return final String[] parameterValues) {
    if (null != parameterValues && parameterValues.length > 0) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        try {
          final IastContext ctx = IastContext.Provider.get();
          for (final String value : parameterValues) {
            module.taint(ctx, value, SourceTypes.REQUEST_PARAMETER_VALUE, paramName);
          }
        } catch (final Throwable e) {
          module.onUnexpectedException("afterGetParameterValues threw", e);
        }
      }
    }
    return parameterValues;
  }

  @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
  @CallSite.After("java.util.Map jakarta.servlet.http.HttpServletRequest.getParameterMap()")
  @CallSite.After("java.util.Map jakarta.servlet.http.HttpServletRequestWrapper.getParameterMap()")
  public static java.util.Map<java.lang.String, java.lang.String[]> afterGetParameterMap(
      @CallSite.This final HttpServletRequest self,
      @CallSite.Return final Map<String, String[]> map) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null && map != null && !map.isEmpty()) {
      try {
        final IastContext ctx = IastContext.Provider.get();
        for (final Map.Entry<String, String[]> entry : map.entrySet()) {
          final String name = entry.getKey();
          module.taint(ctx, name, SourceTypes.REQUEST_PARAMETER_NAME, name);
          for (final String value : entry.getValue()) {
            module.taint(ctx, value, SourceTypes.REQUEST_PARAMETER_VALUE, name);
          }
        }
      } catch (final Throwable e) {
        module.onUnexpectedException("afterGetParameter threw", e);
      }
    }
    return map;
  }

  @Sink(VulnerabilityTypes.UNVALIDATED_REDIRECT)
  @CallSite.Before(
      "jakarta.servlet.RequestDispatcher jakarta.servlet.http.HttpServletRequest.getRequestDispatcher(java.lang.String)")
  @CallSite.Before(
      "jakarta.servlet.RequestDispatcher jakarta.servlet.http.HttpServletRequestWrapper.getRequestDispatcher(java.lang.String)")
  public static void beforeRequestDispatcher(@CallSite.Argument final String path) {
    final UnvalidatedRedirectModule module = InstrumentationBridge.UNVALIDATED_REDIRECT;
    if (module != null && path != null) {
      try {
        module.onRedirect(path);
      } catch (final Throwable e) {
        module.onUnexpectedException("beforeRequestDispatcher threw", e);
      }
    }
  }
}
