package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.io.InputStreamReader;
import javax.annotation.Nonnull;

@Propagation
@CallSite(spi = IastCallSites.class)
public class InputStreamReaderCallSite {

  @CallSite.After("void java.io.InputStreamReader.<init>(java.io.InputStream)")
  @CallSite.After(
      "void java.io.InputStreamReader.<init>(java.io.InputStream, java.nio.charset.Charset)")
  public static InputStreamReader afterInit(
      @CallSite.AllArguments @Nonnull final Object[] params,
      @CallSite.Return @Nonnull final InputStreamReader result) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null) {
      try {
        module.taintObjectIfTainted(result, params[0]);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterInit threw", e);
      }
    }
    return result;
  }
}
