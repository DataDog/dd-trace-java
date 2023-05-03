package datadog.trace.plugin.csi.impl.ext.tests;

import datadog.trace.agent.tooling.csi.CallSite;
import javax.servlet.http.HttpServletRequest;

@CallSite(spi = IastAdvice.class)
public class IastExtensionCallSite {

  @IastAdvice.Source(SourceTypes.REQUEST_HEADER_VALUE)
  @CallSite.After(
      "java.lang.String javax.servlet.http.HttpServletRequest.getHeader(java.lang.String)")
  @CallSite.After(
      "java.lang.String javax.servlet.http.HttpServletRequestWrapper.getHeader(java.lang.String)")
  public static String afterGetHeader(
      @CallSite.This final HttpServletRequest self,
      @CallSite.Argument final String headerName,
      @CallSite.Return final String headerValue) {
    return headerValue;
  }
}
