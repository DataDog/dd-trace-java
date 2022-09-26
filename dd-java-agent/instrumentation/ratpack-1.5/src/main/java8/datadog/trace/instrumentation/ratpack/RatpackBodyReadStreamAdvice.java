package datadog.trace.instrumentation.ratpack;

import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.http.StoredBodyFactories;
import datadog.trace.api.http.StoredByteBody;
import io.netty.buffer.ByteBuf;
import net.bytebuddy.asm.Advice;
import ratpack.file.FileIo;
import ratpack.server.internal.RequestBody;
import ratpack.stream.TransformablePublisher;

@RequiresRequestContext(RequestContextSlot.APPSEC)
public class RatpackBodyReadStreamAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  static void after(
      @Advice.This RequestBody requestBody,
      @Advice.Return(readOnly = false) TransformablePublisher<ByteBuf> publisher) {
    final StoredByteBody byteBody =
        StoredBodyFactories.maybeCreateForByte(null, requestBody.getContentLength());
    if (byteBody == null) {
      return;
    }

    publisher = new RequestBodyCollectionPublisher(byteBody, publisher);
  }

  public void muzzleCheck() {
    FileIo.open(null); // added in 1.5
  }
}
