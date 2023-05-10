package datadog.trace.instrumentation.vertx_4_0.server;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.vertx.core.Handler;

/**
 * Reports a blocking exception on the netty span, using the vertx exception handler mechanism.
 *
 * @see VertxImplInstrumentation
 */
public class BlockingExceptionHandler implements Handler<Throwable> {
  private final AgentSpan span;
  private final Handler<Throwable> delegate;

  public BlockingExceptionHandler(AgentSpan span, Handler<Throwable> delegate) {
    this.span = span;
    this.delegate = delegate;
  }

  @Override
  public void handle(Throwable event) {
    if (event instanceof BlockingException) {
      VertxDecorator.DECORATE.onError(span, event);
    }

    if (this.delegate != null) {
      this.delegate.handle(event);
    }
  }
}
