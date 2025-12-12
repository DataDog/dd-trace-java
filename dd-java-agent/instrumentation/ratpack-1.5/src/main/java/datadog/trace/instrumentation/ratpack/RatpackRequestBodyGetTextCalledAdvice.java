package datadog.trace.instrumentation.ratpack;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.http.StoredBodyFactories;
import datadog.trace.bootstrap.InstrumentationContext;
import net.bytebuddy.asm.Advice;
import ratpack.file.FileIo;
import ratpack.http.internal.ByteBufBackedTypedData;

@RequiresRequestContext(RequestContextSlot.APPSEC)
public class RatpackRequestBodyGetTextCalledAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  static void after(
      @Advice.This ByteBufBackedTypedData thiz,
      @Advice.Return String str,
      @ActiveRequestContext RequestContext reqCtx,
      @Advice.Thrown(readOnly = false) Throwable throwable) {
    Boolean bodyPublished =
        InstrumentationContext.get(ByteBufBackedTypedData.class, Boolean.class).get(thiz);
    if (bodyPublished == Boolean.TRUE) {
      return;
    }
    InstrumentationContext.get(ByteBufBackedTypedData.class, Boolean.class).put(thiz, Boolean.TRUE);

    Flow<Void> flow = StoredBodyFactories.maybeDeliverBodyInOneGo(str, reqCtx);
    Flow.Action action = flow.getAction();
    if (action instanceof Flow.Action.RequestBlockingAction) {
      BlockResponseFunction blockResponseFunction = reqCtx.getBlockResponseFunction();
      if (blockResponseFunction == null) {
        return;
      }
      Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
      blockResponseFunction.tryCommitBlockingResponse(reqCtx.getTraceSegment(), rba);
      if (throwable == null) {
        throwable = new BlockingException("Blocked request (for ByteBufBackedTypedData/getText)");
      }
    }
  }

  public void muzzleCheck() {
    FileIo.open(null); // added in 1.5
  }
}
