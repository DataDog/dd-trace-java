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
import java.io.OutputStream;
import net.bytebuddy.asm.Advice;
import ratpack.file.FileIo;
import ratpack.http.internal.ByteBufBackedTypedData;

/**
 * Calling getText doesn't modify the underlying buffer (doesn't move read index)
 *
 * <p>See instrumented methods.
 *
 * @see ByteBufBackedTypedData#getBuffer()
 * @see ByteBufBackedTypedData#getBytes()
 * @see ByteBufBackedTypedData#writeTo(OutputStream)
 * @see ByteBufBackedTypedData#getInputStream()
 */
@RequiresRequestContext(RequestContextSlot.APPSEC)
public class RatpackRequestBodyCallGetBufferAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  static Throwable before(
      @Advice.This ByteBufBackedTypedData thiz, @ActiveRequestContext RequestContext reqCtx) {
    Boolean bodyPublished =
        InstrumentationContext.get(ByteBufBackedTypedData.class, Boolean.class).get(thiz);
    if (bodyPublished == Boolean.TRUE) {
      return null;
    }
    InstrumentationContext.get(ByteBufBackedTypedData.class, Boolean.class).put(thiz, Boolean.TRUE);

    Flow<Void> flow =
        StoredBodyFactories.maybeDeliverBodyInOneGo(new GetTextCharSequenceSupplier(thiz), reqCtx);
    Flow.Action action = flow.getAction();
    if (action instanceof Flow.Action.RequestBlockingAction) {
      BlockResponseFunction blockResponseFunction = reqCtx.getBlockResponseFunction();
      if (blockResponseFunction == null) {
        return null;
      }
      Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
      blockResponseFunction.tryCommitBlockingResponse(reqCtx.getTraceSegment(), rba);
      return new BlockingException("Blocked request (for ByteBufBackedTypedData/getBuffer)");
    }

    return null;
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  static void after(
      @Advice.Enter Throwable enterThr,
      @Advice.Thrown(readOnly = false) Throwable t,
      @ActiveRequestContext RequestContext reqCtx) {
    if (enterThr == null) {
      return;
    }

    // it's questionable, but we don't replace existing exceptions with our BlockingException
    if (t == null) {
      t = enterThr;
    }
  }

  public void muzzleCheck() {
    FileIo.open(null); // added in 1.5
  }
}
