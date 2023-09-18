package datadog.trace.instrumentation.groovy;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.csi.SkipDynamicHelpers;
import datadog.trace.api.iast.propagation.StringModule;
import org.codehaus.groovy.runtime.GStringImpl;

@SuppressWarnings("unused")
@Propagation
@CallSite(spi = IastCallSites.class)
@SkipDynamicHelpers
public class GStringCallSite {

  @CallSite.After(
      "void org.codehaus.groovy.runtime.GStringImpl.<init>(java.lang.Object[], java.lang.String[])")
  public static GStringImpl afterInit(
      @CallSite.AllArguments final Object[] args, @CallSite.Return final GStringImpl result) {
    final StringModule stringModule = InstrumentationBridge.STRING;
    if (stringModule != null) {
      try {
        final Object[] params = (Object[]) args[0];
        final String[] literals = (String[]) args[1];
        stringModule.onStringFormat(literals, params, result);
      } catch (final Throwable e) {
        stringModule.onUnexpectedException("after init threw", e);
      }
    }
    return result;
  }
}
