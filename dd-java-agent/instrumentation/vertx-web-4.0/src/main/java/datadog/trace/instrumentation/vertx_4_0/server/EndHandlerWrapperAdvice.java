package datadog.trace.instrumentation.vertx_4_0.server;

import io.vertx.core.Handler;
import net.bytebuddy.asm.Advice;

public class EndHandlerWrapperAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void wrapHandler(
      @Advice.FieldValue(value = "endHandler", readOnly = false) final Handler<Void> endHandler,
      @Advice.Argument(value = 0, readOnly = false) Handler<Void> handler) {
    // In case the handler instrumentation executes twice on the same response
    if (endHandler instanceof EndHandlerWrapper && handler instanceof EndHandlerWrapper) {
      return;
    }
    // If an end handler was already registered when our wrapper is registered, we save the one that
    // existed before
    if (handler instanceof EndHandlerWrapper && endHandler != null) {
      ((EndHandlerWrapper) handler).actual = endHandler;

      // If the user registers an end handler and ours has already been registered then we wrap the
      // users handler and swap the function argument for the wrapper
    } else if (endHandler instanceof EndHandlerWrapper) {
      ((EndHandlerWrapper) endHandler).actual = handler;
      handler = endHandler;
    }
  }
}
