package datadog.trace.plugin.csi.impl.ext.tests;

import datadog.trace.agent.tooling.csi.CallSite;
import java.io.BufferedReader;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;

@CallSite(spi = IastCallSites.class)
public class IastExtensionCallSite {

  @Source(SourceTypes.REQUEST_HEADER_NAME_STRING)
  @CallSite.After(
      "java.lang.String javax.servlet.http.HttpServletRequest.getHeader(java.lang.String)")
  public static String afterGetHeader(
      @CallSite.This final HttpServletRequest self,
      @CallSite.Argument final String headerName,
      @CallSite.Return final String headerValue) {
    return headerValue;
  }

  @Propagation
  @CallSite.After("java.io.BufferedReader javax.servlet.ServletRequest.getReader()")
  public static BufferedReader afterGetReader(
      @CallSite.This final ServletRequest self,
      @CallSite.Return final BufferedReader bufferedReader) {
    return bufferedReader;
  }
}
