package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.UntrustedDeserializationModule;
import java.io.InputStream;

@Sink(VulnerabilityTypes.UNTRUSTED_DESERIALIZATION)
@CallSite(spi = IastCallSites.class)
public class ObjectInputStreamCallSite {

  @CallSite.Before("void java.io.ObjectInputStream.<init>(java.io.InputStream)")
  public static void beforeConstructorUntrusted(@CallSite.Argument(0) final InputStream is) {
    final UntrustedDeserializationModule module = InstrumentationBridge.UNTRUSTED_DESERIALIZATION;

    if (module != null) {
      try {
        module.onObject(is);
      } catch (Throwable e) {
        module.onUnexpectedException("before constructor untrusted threw", e);
      }
    }
  }
}
