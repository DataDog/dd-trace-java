package datadog.trace.instrumentation.websocket.jsr256;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.websocket.jsr256.HandlersExtractor.MESSAGE_TYPE_TEXT;
import static datadog.trace.instrumentation.websocket.jsr256.WebsocketDecorator.DECORATE;

import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import java.io.IOException;
import java.io.Writer;

public class TracingWriter extends Writer {
  private final Writer delegate;
  private final HandlerContext.Sender handlerContext;

  public TracingWriter(Writer delegate, HandlerContext.Sender handlerContext) {
    this.delegate = delegate;
    this.handlerContext = handlerContext;
  }

  @Override
  public void write(char[] cbuf, int off, int len) throws IOException {
    final boolean doTrace = CallDepthThreadLocalMap.incrementCallDepth(HandlerContext.class) == 0;
    if (doTrace) {
      DECORATE.onSendFrameStart(handlerContext, MESSAGE_TYPE_TEXT, len);
    }
    try (final AgentScope ignored = activateSpan(handlerContext.getWebsocketSpan())) {
      delegate.write(cbuf, off, len);
    } finally {
      if (doTrace) {
        CallDepthThreadLocalMap.reset(HandlerContext.class);
      }
    }
  }

  @Override
  public void flush() throws IOException {
    delegate.flush();
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
