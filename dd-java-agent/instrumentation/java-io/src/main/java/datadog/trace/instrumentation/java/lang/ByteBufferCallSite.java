package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.nio.ByteBuffer;
import javax.annotation.Nullable;

@Propagation
@CallSite(spi = IastCallSites.class)
public class ByteBufferCallSite {

  @CallSite.After("java.nio.ByteBuffer java.nio.ByteBuffer.wrap(byte[])")
  public static ByteBuffer afterWrap(
      @CallSite.Argument @Nullable final byte[] bytes, @CallSite.Return final ByteBuffer result) {
    if (bytes == null || bytes.length == 0) {
      return result;
    }
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module == null) {
      return result;
    }
    try {
      module.taintIfTainted(result, bytes);
    } catch (final Throwable e) {
      module.onUnexpectedException("beforeConstructor threw", e);
    }
    return result;
  }
}
