package datadog.trace.instrumentation.vertx_3_4.server;

import io.vertx.core.Handler;
import net.bytebuddy.asm.Advice;

public class ExceptionHandlerWrapperAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void wrapHandler(
      @Advice.FieldValue(value = "exceptionHandler", readOnly = false)
          final Handler<Throwable> exceptionHandler,
      @Advice.Argument(value = 0, readOnly = false) Handler<Throwable> handler) {
    // In case the handler instrumentation executes twice on the same response
    if (exceptionHandler instanceof ExceptionHandlerWrapper
        && handler instanceof ExceptionHandlerWrapper) {
      return;
    }
    // If an exception handler was already registered when our wrapper is registered, we save the
    // one that existed before
    if (handler instanceof ExceptionHandlerWrapper && exceptionHandler != null) {
      ((ExceptionHandlerWrapper) handler).actual = exceptionHandler;

      // If the user registers an exception handler and ours has already been registered then we
      // wrap the user's handler and swap the function argument for the wrapper
    } else if (exceptionHandler instanceof ExceptionHandlerWrapper) {
      ((ExceptionHandlerWrapper) exceptionHandler).actual = handler;
      handler = exceptionHandler;
    }
  }
}
