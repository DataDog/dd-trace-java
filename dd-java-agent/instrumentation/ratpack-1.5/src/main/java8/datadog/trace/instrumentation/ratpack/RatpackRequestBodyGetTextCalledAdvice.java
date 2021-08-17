package datadog.trace.instrumentation.ratpack;

import datadog.trace.api.http.StoredBodyFactories;
import datadog.trace.bootstrap.InstrumentationContext;
import net.bytebuddy.asm.Advice;
import ratpack.http.internal.ByteBufBackedTypedData;

public class RatpackRequestBodyGetTextCalledAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  static void after(@Advice.This ByteBufBackedTypedData thiz, @Advice.Return String str) {
    Boolean bodyPublished =
        InstrumentationContext.get(ByteBufBackedTypedData.class, Boolean.class).get(thiz);
    if (bodyPublished == Boolean.TRUE) {
      return;
    }
    InstrumentationContext.get(ByteBufBackedTypedData.class, Boolean.class).put(thiz, Boolean.TRUE);

    StoredBodyFactories.maybeDeliverBodyInOneGo(str); // TODO: blocking
  }
}
