package datadog.trace.instrumentation.ratpack;

import datadog.trace.api.http.StoredBodyFactories;
import datadog.trace.api.http.StoredByteBody;
import io.netty.buffer.ByteBuf;
import net.bytebuddy.asm.Advice;
import ratpack.server.internal.RequestBody;
import ratpack.stream.TransformablePublisher;

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
}
