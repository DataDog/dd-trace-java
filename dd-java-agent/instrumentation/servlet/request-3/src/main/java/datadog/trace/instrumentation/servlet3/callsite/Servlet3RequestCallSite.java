package datadog.trace.instrumentation.servlet3.callsite;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.InstrumentationBridge;
import java.util.Map;
import javax.servlet.ServletRequest;

@CallSite(spi = IastAdvice.class)
public class Servlet3RequestCallSite {

  @CallSite.AfterArray({
    @CallSite.After("java.util.Map javax.servlet.ServletRequest.getParameterMap()"),
    @CallSite.After("java.util.Map javax.servlet.http.HttpServletRequest.getParameterMap()"),
    @CallSite.After("java.util.Map javax.servlet.http.HttpServletRequestWrapper.getParameterMap()"),
    @CallSite.After("java.util.Map javax.servlet.ServletRequestWrapper.getParameterMap()")
  })
  public static java.util.Map<java.lang.String, java.lang.String[]> aroundGetParameterMap(
      @CallSite.This final ServletRequest self, @CallSite.Return final Map<String, String[]> map) {
    for (Map.Entry<String, String[]> entry : map.entrySet()) {
      for (String value : entry.getValue()) {
        InstrumentationBridge.onParameterValue(entry.getKey(), value);
      }
    }
    return map;
  }
}
