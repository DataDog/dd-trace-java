package datadog.trace.instrumentation.springwebflux.server.iast;

import com.fasterxml.jackson.databind.util.TokenBuffer;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import net.bytebuddy.asm.Advice;
import org.springframework.core.io.buffer.DataBuffer;
import reactor.core.publisher.Flux;

@RequiresRequestContext(RequestContextSlot.IAST)
class Jackson2TokenizerApplyAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  @Source(SourceTypes.REQUEST_BODY)
  public static void after(
      @Advice.Argument(0) DataBuffer dataBuffer,
      @Advice.Return(readOnly = false) Flux<TokenBuffer> flux,
      @ActiveRequestContext RequestContext reqCtx) {
    PropagationModule propagation = InstrumentationBridge.PROPAGATION;
    if (propagation == null || flux == null || dataBuffer == null) {
      return;
    }
    IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
    if (!propagation.isTainted(ctx, dataBuffer)) {
      return;
    }
    flux = flux.map(new TaintFluxElementsFunction<>(ctx, propagation));
  }
}
