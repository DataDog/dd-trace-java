package datadog.trace.instrumentation.springwebflux.server.iast;

import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.taint.TaintedObjects;
import net.bytebuddy.asm.Advice;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.ReactiveHttpInputMessage;
import reactor.core.publisher.Flux;

/** @see ReactiveHttpInputMessage#getBody() */
class TaintGetBodyAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void after(@Advice.Return(readOnly = false) Flux<DataBuffer> flux) {
    PropagationModule propagation = InstrumentationBridge.PROPAGATION;
    if (propagation == null || flux == null) {
      return;
    }

    // taint both the flux and the individual DataBuffers
    final TaintedObjects to = IastContext.Provider.taintedObjects();
    propagation.taintObject(to, flux, SourceTypes.REQUEST_BODY);
    flux = flux.map(new TaintFluxElementsFunction<>(to, propagation));
  }
}
