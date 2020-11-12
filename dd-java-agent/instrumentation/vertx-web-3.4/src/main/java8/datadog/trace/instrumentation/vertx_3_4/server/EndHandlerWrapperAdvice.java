package datadog.trace.instrumentation.vertx_3_4.server;

import io.vertx.core.Handler;
import net.bytebuddy.asm.Advice;

public class EndHandlerWrapperAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void wrapHandler(
      @Advice.FieldValue(value = "endHandler", readOnly = false) final Handler<Void> endHandler,
      @Advice.Argument(value = 0, readOnly = false) final Handler<Void> handler) {
    if (endHandler instanceof EndHandlerWrapper && handler instanceof EndHandlerWrapper) {
      return;
    }
    if (handler instanceof EndHandlerWrapper && endHandler != null) {
      ((EndHandlerWrapper) handler).actual = endHandler;
    } else if (endHandler instanceof EndHandlerWrapper) {
      ((EndHandlerWrapper) endHandler).actual = handler;
    }
  }
}
