package datadog.trace.instrumentation.servlet3.callsite;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.IastAdvice.Source;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.util.Map;
import javax.servlet.ServletRequest;

@CallSite(spi = IastAdvice.class)
public class Servlet3RequestCallSite {

  @Source(SourceTypes.REQUEST_PARAMETER_VALUE_STRING)
  @CallSite.After("java.util.Map javax.servlet.ServletRequest.getParameterMap()")
  @CallSite.After("java.util.Map javax.servlet.http.HttpServletRequest.getParameterMap()")
  @CallSite.After("java.util.Map javax.servlet.http.HttpServletRequestWrapper.getParameterMap()")
  @CallSite.After("java.util.Map javax.servlet.ServletRequestWrapper.getParameterMap()")
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
