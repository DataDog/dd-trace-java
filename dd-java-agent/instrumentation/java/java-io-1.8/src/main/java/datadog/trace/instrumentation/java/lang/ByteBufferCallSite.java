package datadog.trace.instrumentation.java.lang;

import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.nio.ByteBuffer;
import javax.annotation.Nonnull;
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
      module.taintObjectIfTainted(result, bytes, true, NOT_MARKED); // keep ranges
    } catch (final Throwable e) {
      module.onUnexpectedException("beforeConstructor threw", e);
    }
    return result;
  }

  @CallSite.After("byte[] java.nio.ByteBuffer.array()")
  public static byte[] afterArray(
      @CallSite.This @Nonnull final ByteBuffer buffer, @CallSite.Return final byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      return bytes;
    }
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module == null) {
      return bytes;
    }
    try {
      module.taintObjectIfTainted(bytes, buffer, true, NOT_MARKED); // keep ranges
    } catch (final Throwable e) {
      module.onUnexpectedException("afterArray threw", e);
    }
    return bytes;
  }
}
