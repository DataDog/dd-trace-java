package datadog.trace.instrumentation.springwebflux.server.iast;

import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import net.bytebuddy.asm.Advice;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.ReactiveHttpInputMessage;
import reactor.core.publisher.Flux;

/** @see ReactiveHttpInputMessage#getBody() */
@RequiresRequestContext(RequestContextSlot.IAST)
class TaintGetBodyAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void after(@Advice.Return(readOnly = false) Flux<DataBuffer> flux) {
    PropagationModule propagation = InstrumentationBridge.PROPAGATION;
    if (propagation == null || flux == null) {
      return;
    }

    // taint both the flux and the individual DataBuffers
    propagation.taintObject(SourceTypes.REQUEST_BODY, flux);
    flux = flux.map(new TaintFluxElementsFunction<>(propagation));
  }
}
