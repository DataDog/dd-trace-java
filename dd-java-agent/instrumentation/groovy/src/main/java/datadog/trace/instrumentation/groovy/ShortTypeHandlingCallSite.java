package datadog.trace.instrumentation.groovy;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.csi.SkipDynamicHelpers;
import datadog.trace.api.iast.propagation.StringModule;

@Propagation
@CallSite(spi = IastCallSites.class)
@SkipDynamicHelpers
public class ShortTypeHandlingCallSite {

  @CallSite.After(
      "java.lang.String org.codehaus.groovy.runtime.typehandling.ShortTypeHandling.castToString(java.lang.Object)")
  public static String afterCastToString(
      @CallSite.Argument(0) final Object object, @CallSite.Return final String result) {
    final StringModule module = InstrumentationBridge.STRING;
    if (module != null && object instanceof CharSequence) {
      try {
        module.onStringToString((CharSequence) object, result);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterCastToString threw", e);
      }
    }
    return result;
  }
}
