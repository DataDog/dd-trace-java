package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.io.StringReader;
import javax.annotation.Nonnull;

@Propagation
@CallSite(spi = IastCallSites.class)
public class StringReaderCallSite {

  @CallSite.After("void java.io.StringReader.<init>(java.lang.String)")
  public static StringReader afterInit(
      @CallSite.AllArguments @Nonnull final Object[] params,
      @CallSite.Return @Nonnull final StringReader result) {
    final PropagationModule propagationModule = InstrumentationBridge.PROPAGATION;
    if (propagationModule != null) {
      try {
        propagationModule.taintObjectIfTainted(result, params[0]);
      } catch (Throwable e) {
        propagationModule.onUnexpectedException("afterInit threw", e);
      }
    }
    return result;
  }
}
