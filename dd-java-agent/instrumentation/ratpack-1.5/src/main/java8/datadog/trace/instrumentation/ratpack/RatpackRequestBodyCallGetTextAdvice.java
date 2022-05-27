package datadog.trace.instrumentation.ratpack;

import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.http.StoredBodyFactories;
import datadog.trace.bootstrap.InstrumentationContext;
import net.bytebuddy.asm.Advice;
import ratpack.file.FileIo;
import ratpack.http.internal.ByteBufBackedTypedData;

// Calling getText doesn't modify the underlying buffer (doesn't move read index)
@RequiresRequestContext(RequestContextSlot.APPSEC)
public class RatpackRequestBodyCallGetTextAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  static void before(
      @Advice.This ByteBufBackedTypedData thiz, @ActiveRequestContext RequestContext reqCtx) {
    Boolean bodyPublished =
        InstrumentationContext.get(ByteBufBackedTypedData.class, Boolean.class).get(thiz);
    if (bodyPublished == Boolean.TRUE) {
      return;
    }
    InstrumentationContext.get(ByteBufBackedTypedData.class, Boolean.class).put(thiz, Boolean.TRUE);

    Flow<Void> flow =
        StoredBodyFactories.maybeDeliverBodyInOneGo(new GetTextCharSequenceSupplier(thiz), reqCtx);
    if (flow.getAction().isBlocking()) {
      // TODO: implement blocking
    }
  }

  public void muzzleCheck() {
    FileIo.open(null); // added in 1.5
  }
}
