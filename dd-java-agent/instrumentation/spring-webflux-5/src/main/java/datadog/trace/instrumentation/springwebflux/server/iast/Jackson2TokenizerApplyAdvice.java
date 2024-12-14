package datadog.trace.instrumentation.springwebflux.server.iast;

import com.fasterxml.jackson.databind.util.TokenBuffer;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.taint.TaintedObjects;
import net.bytebuddy.asm.Advice;
import org.springframework.core.io.buffer.DataBuffer;
import reactor.core.publisher.Flux;

class Jackson2TokenizerApplyAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  @Source(SourceTypes.REQUEST_BODY)
  public static void after(
      @Advice.Argument(0) DataBuffer dataBuffer,
      @Advice.Return(readOnly = false) Flux<TokenBuffer> flux) {
    PropagationModule propagation = InstrumentationBridge.PROPAGATION;
    if (propagation == null || flux == null || dataBuffer == null) {
      return;
    }
    final TaintedObjects to = IastContext.Provider.taintedObjects();
    if (!propagation.isTainted(to, dataBuffer)) {
      return;
    }
    flux = flux.map(new TaintFluxElementsFunction<>(to, propagation));
  }
}
