package datadog.trace.instrumentation.servlet.request;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.InstrumentationBridge;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
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
    InstrumentationBridge.onHeaderValue(headerName, headerValue);
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
      @CallSite.Return final Enumeration<?> enumeration) {
    if (enumeration == null) {
      return null;
    }
    final List<String> result = new ArrayList<>();
    while (enumeration.hasMoreElements()) {
      final String headerValue = (String) enumeration.nextElement();
      InstrumentationBridge.onHeaderValue(headerName, headerValue);
      result.add(headerValue);
    }
    return Collections.enumeration(result);
  }

  @CallSite.AfterArray({
    @CallSite.After("java.util.Enumeration javax.servlet.http.HttpServletRequest.getHeaderNames()"),
    @CallSite.After(
        "java.util.Enumeration javax.servlet.http.HttpServletRequestWrapper.getHeaderNames()"),
  })
  public static Enumeration<?> afterGetHeaderNames(
      @CallSite.This final HttpServletRequest self,
      @CallSite.Return final Enumeration<?> enumeration) {
    if (enumeration == null) {
      return null;
    }
    final List<String> result = new ArrayList<>();
    while (enumeration.hasMoreElements()) {
      final String headerName = (String) enumeration.nextElement();
      InstrumentationBridge.onHeaderName(headerName);
      result.add(headerName);
    }
    return Collections.enumeration(result);
  }
}
