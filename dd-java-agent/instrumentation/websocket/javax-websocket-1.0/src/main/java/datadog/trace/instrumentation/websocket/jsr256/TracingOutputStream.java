package datadog.trace.instrumentation.websocket.jsr256;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.WebsocketDecorator.DECORATE;
import static datadog.trace.bootstrap.instrumentation.websocket.HandlersExtractor.MESSAGE_TYPE_BINARY;

import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.websocket.HandlerContext;
import java.io.IOException;
import java.io.OutputStream;

public class TracingOutputStream extends OutputStream {
  private final OutputStream delegate;
  private final HandlerContext.Sender handlerContext;

  public TracingOutputStream(OutputStream delegate, HandlerContext.Sender handlerContext) {
    super();
    this.delegate = delegate;
    this.handlerContext = handlerContext;
  }

  @Override
  public void write(int b) throws IOException {
    final boolean doTrace = CallDepthThreadLocalMap.incrementCallDepth(HandlerContext.class) == 0;
    if (doTrace) {
      DECORATE.onSendFrameStart(handlerContext, MESSAGE_TYPE_BINARY, 1);
    }
    try (final AgentScope ignored = activateSpan(handlerContext.getWebsocketSpan())) {
      delegate.write(b);
    } finally {
      if (doTrace) {
        CallDepthThreadLocalMap.reset(HandlerContext.class);
      }
    }
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    final boolean doTrace = CallDepthThreadLocalMap.incrementCallDepth(HandlerContext.class) == 0;
    if (doTrace) {
      DECORATE.onSendFrameStart(handlerContext, MESSAGE_TYPE_BINARY, len);
    }
    try (final AgentScope ignored = activateSpan(handlerContext.getWebsocketSpan())) {
      delegate.write(b, off, len);
    } finally {
      if (doTrace) {
        CallDepthThreadLocalMap.reset(HandlerContext.class);
      }
    }
  }

  @Override
  public void close() throws IOException {
    final boolean doTrace = CallDepthThreadLocalMap.incrementCallDepth(HandlerContext.class) == 0;
    try (final AgentScope ignored =
        handlerContext.getWebsocketSpan() != null
            ? activateSpan(handlerContext.getWebsocketSpan())
            : null) {
      delegate.close();
    } finally {
      if (doTrace) {
        CallDepthThreadLocalMap.reset(HandlerContext.class);
        DECORATE.onFrameEnd(handlerContext);
      }
    }
  }
}
