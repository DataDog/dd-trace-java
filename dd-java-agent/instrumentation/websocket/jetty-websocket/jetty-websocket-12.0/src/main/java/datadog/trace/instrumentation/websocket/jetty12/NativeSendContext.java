package datadog.trace.instrumentation.websocket.jetty12;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.WebsocketDecorator.DECORATE;
import static datadog.trace.bootstrap.instrumentation.websocket.HandlersExtractor.MESSAGE_TYPE_BINARY;
import static datadog.trace.bootstrap.instrumentation.websocket.HandlersExtractor.MESSAGE_TYPE_TEXT;

import datadog.context.ContextScope;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.websocket.HandlerContext;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.core.Behavior;
import org.eclipse.jetty.websocket.core.CoreSession;

/** Tracks message boundaries independently of asynchronous send completion. */
public class NativeSendContext {
  private final AgentSpan handshakeSpan;
  private final String sessionId;
  private final boolean client;
  private final Set<Message> pending = new HashSet<>();
  private Message partialMessage;
  private boolean closed;

  public NativeSendContext(AgentSpan span, CoreSession session) {
    if (Config.get().isWebsocketMessagesInheritSampling()) {
      span.forceSamplingDecision();
    }
    client = session.getBehavior() == Behavior.CLIENT;
    handshakeSpan = client ? span : span.getLocalRootSpan();
    sessionId = Integer.toHexString(System.identityHashCode(session));
  }

  public synchronized SendCallback start(
      Object payload, boolean binary, boolean partial, boolean last, Callback callback) {
    if (closed) {
      return null;
    }
    CharSequence type = binary ? MESSAGE_TYPE_BINARY : MESSAGE_TYPE_TEXT;
    int size =
        payload == null
            ? 0
            : binary ? ((ByteBuffer) payload).remaining() : ((String) payload).length();
    Message message = partial ? partialMessage : null;
    if (message == null || !type.equals(message.getMessageType())) {
      // Later fragments keep the span created under the first fragment's parent.
      if (client && activeSpan() == null) {
        return null;
      }
      message = new Message(handshakeSpan, sessionId);
      pending.add(message);
    }
    AgentSpan span = DECORATE.startOutboundFrameSpan(message, type, size);
    message.pendingCallbacks++;
    message.complete = last;
    if (partial && (partialMessage == null || partialMessage == message)) {
      partialMessage = last ? null : message;
    }
    return new SendCallback(this, message, span, callback);
  }

  public synchronized void finish() {
    closed = true;
    for (Iterator<Message> iterator = pending.iterator(); iterator.hasNext(); ) {
      Message message = iterator.next();
      // Close ends the message, but pending callbacks still determine the send outcome.
      message.complete = true;
      if (message.pendingCallbacks == 0) {
        message.finished = true;
        DECORATE.onFrameEnd(message);
        iterator.remove();
      }
    }
    partialMessage = null;
  }

  public static class Message extends HandlerContext.Sender {
    private int pendingCallbacks;
    private boolean complete;
    private boolean finished;

    public Message(AgentSpan handshakeSpan, String sessionId) {
      super(handshakeSpan, sessionId);
    }
  }

  public static class SendCallback implements Callback {
    private final NativeSendContext context;
    private final Message message;
    private final AgentSpan span;
    private final Callback delegate;
    private boolean completed;
    private boolean methodExited;
    private boolean released;

    public SendCallback(
        NativeSendContext context, Message message, AgentSpan span, Callback delegate) {
      this.context = context;
      this.message = message;
      this.span = span;
      this.delegate = delegate;
    }

    public AgentSpan span() {
      return span;
    }

    @Override
    public void succeed() {
      complete(null);
    }

    @Override
    public void fail(Throwable failure) {
      complete(failure);
    }

    private void complete(Throwable failure) {
      synchronized (context) {
        onError(failure);
      }
      try (ContextScope ignored = activateSpan(span)) {
        try {
          if (delegate != null) {
            if (failure == null) {
              delegate.succeed();
            } else {
              delegate.fail(failure);
            }
          }
        } catch (Throwable t) {
          synchronized (context) {
            onError(t);
          }
          throw t;
        }
      } finally {
        synchronized (context) {
          completed = true;
          finish();
        }
      }
    }

    public void onMethodExit(ContextScope scope, Throwable failure) {
      synchronized (context) {
        try {
          onError(failure);
        } finally {
          if (scope != null) {
            scope.close();
          }
          if (failure != null) {
            completed = true;
          }
          methodExited = true;
          finish();
        }
      }
    }

    private void onError(Throwable failure) {
      synchronized (context) {
        if (failure != null && !message.finished) {
          DECORATE.onError(span, failure);
          message.complete = true;
          if (context.partialMessage == message) {
            context.partialMessage = null;
          }
        }
      }
    }

    private void finish() {
      // Synchronous completion must wait for the send method's scope to close.
      if (completed && methodExited && !released) {
        released = true;
        if (--message.pendingCallbacks == 0 && message.complete && !message.finished) {
          message.finished = true;
          DECORATE.onFrameEnd(message);
          context.pending.remove(message);
        }
      }
    }
  }
}
