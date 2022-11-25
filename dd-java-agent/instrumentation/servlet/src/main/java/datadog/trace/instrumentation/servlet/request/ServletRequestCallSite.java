package datadog.trace.instrumentation.servlet.request;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.InstrumentationBridge;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import javax.servlet.ServletRequest;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

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
    InstrumentationBridge.onParameterValue(paramName, paramValue);
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
  public static Enumeration afterGetParameterNames(
      @CallSite.This final ServletRequest self, @CallSite.Return final Enumeration enumeration) {
    ArrayList<String> parameterNames = new ArrayList<>();
    while (enumeration.hasMoreElements()) {
      String paramName = (String) enumeration.nextElement();
      InstrumentationBridge.onParameterName(paramName);
      parameterNames.add(paramName);
    }
    return Collections.enumeration(parameterNames);
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
      for (String paramValue : parameterValues) {
        InstrumentationBridge.onParameterValue(paramName, paramValue);
      }
    }
    return parameterValues;
  }

  @CallSite.AfterArray({
    @CallSite.After(
        "javax.servlet.http.Cookie[] javax.servlet.http.HttpServletRequest.getCookies()"),
    @CallSite.After(
        "javax.servlet.http.Cookie[] javax.servlet.http.HttpServletRequestWrapper.getCookies()")
  })
  public static Cookie[] afterGetCookies(
      @CallSite.This final HttpServletRequest self, @CallSite.Return final Cookie[] cookies) {
    if (null != cookies) {
      for (Cookie cookie : cookies) {
        InstrumentationBridge.onCookie(
            cookie.getComment(),
            cookie.getDomain(),
            cookie.getValue(),
            cookie.getName(),
            cookie.getPath());
      }
    }
    return cookies;
  }
}
