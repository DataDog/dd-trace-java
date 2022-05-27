package datadog.trace.instrumentation.ratpack;

import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.http.StoredBodyFactories;
import datadog.trace.bootstrap.InstrumentationContext;
import net.bytebuddy.asm.Advice;
import ratpack.file.FileIo;
import ratpack.http.internal.ByteBufBackedTypedData;

@RequiresRequestContext(RequestContextSlot.APPSEC)
public class RatpackRequestBodyGetTextCalledAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  static void after(
      @Advice.This ByteBufBackedTypedData thiz,
      @Advice.Return String str,
      @ActiveRequestContext RequestContext reqCtx) {
    Boolean bodyPublished =
        InstrumentationContext.get(ByteBufBackedTypedData.class, Boolean.class).get(thiz);
    if (bodyPublished == Boolean.TRUE) {
      return;
    }
    InstrumentationContext.get(ByteBufBackedTypedData.class, Boolean.class).put(thiz, Boolean.TRUE);

    StoredBodyFactories.maybeDeliverBodyInOneGo(str, reqCtx); // TODO: blocking
  }

  public void muzzleCheck() {
    FileIo.open(null); // added in 1.5
  }
}
