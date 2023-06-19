package datadog.trace.instrumentation.springwebflux.server.iast;

import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.io.InputStream;
import net.bytebuddy.asm.Advice;
import org.springframework.core.io.buffer.DataBuffer;

@RequiresRequestContext(RequestContextSlot.IAST)
public class DataBufferAsInputStreamAdvice {

  @Advice.OnMethodExit(suppress = Throwable.class)
  @Propagation
  public static void after(@Advice.This DataBuffer dataBuffer, @Advice.Return InputStream is) {
    PropagationModule mod = InstrumentationBridge.PROPAGATION;

    if (mod == null || is == null) {
      return;
    }

    mod.taintIfInputIsTainted(is, dataBuffer);
  }
}
