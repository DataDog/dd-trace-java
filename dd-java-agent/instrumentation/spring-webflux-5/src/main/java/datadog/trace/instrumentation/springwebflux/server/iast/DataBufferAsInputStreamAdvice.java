package datadog.trace.instrumentation.springwebflux.server.iast;

import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.taint.TaintedObjects;
import java.io.InputStream;
import net.bytebuddy.asm.Advice;
import org.springframework.core.io.buffer.DataBuffer;

public class DataBufferAsInputStreamAdvice {

  @Advice.OnMethodExit(suppress = Throwable.class)
  @Propagation
  public static void after(@Advice.This DataBuffer dataBuffer, @Advice.Return InputStream is) {
    PropagationModule mod = InstrumentationBridge.PROPAGATION;

    if (mod == null || is == null) {
      return;
    }
    final TaintedObjects to = IastContext.Provider.taintedObjects();
    mod.taintObjectIfTainted(to, is, dataBuffer);
  }
}
