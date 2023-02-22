package datadog.trace.instrumentation.servlet3.callsite;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.source.WebModule;
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
  public static java.util.Map<java.lang.String, java.lang.String[]> afterGetParameterMap(
      @CallSite.This final ServletRequest self, @CallSite.Return final Map<String, String[]> map) {
    final WebModule module = InstrumentationBridge.WEB;
    if (module != null) {
      try {
        for (Map.Entry<String, String[]> entry : map.entrySet()) {
          module.onParameterName(entry.getKey());
          for (String value : entry.getValue()) {
            module.onParameterValue(entry.getKey(), value);
          }
        }
      } catch (final Throwable e) {
        module.onUnexpectedException("afterGetParameter threw", e);
      }
    }
    return map;
  }
}
