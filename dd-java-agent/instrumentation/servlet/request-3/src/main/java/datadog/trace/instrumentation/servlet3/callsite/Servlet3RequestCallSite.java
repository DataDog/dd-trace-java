package datadog.trace.instrumentation.servlet3.callsite;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.util.Map;
import javax.servlet.ServletRequest;

@CallSite(spi = IastCallSites.class)
public class Servlet3RequestCallSite {

  @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
  @CallSite.After("java.util.Map javax.servlet.ServletRequest.getParameterMap()")
  @CallSite.After("java.util.Map javax.servlet.ServletRequestWrapper.getParameterMap()")
  public static java.util.Map<java.lang.String, java.lang.String[]> afterGetParameterMap(
      @CallSite.This final ServletRequest self, @CallSite.Return final Map<String, String[]> map) {
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
}
