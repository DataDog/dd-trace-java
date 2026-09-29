package datadog.trace.instrumentation.websocket.jetty12;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.WebsocketDecorator.DECORATE;
import static java.lang.invoke.MethodHandles.dropArguments;
import static java.lang.invoke.MethodHandles.insertArguments;

import datadog.context.ContextScope;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.ExceptionLogger;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.websocket.HandlerContext;
import datadog.trace.util.MethodHandles;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.core.CoreSession;

/**
 * Wraps Jetty's endpoint handles after argument normalization, preserving their invocation types.
 */
public class NativeMethodHandleWrappers {
  private static final MethodHandles LOOKUP =
      new MethodHandles(NativeMethodHandleWrappers.class.getClassLoader());
  private static final MethodHandle TEXT =
      LOOKUP.method(
          NativeMethodHandleWrappers.class,
          "onText",
          MethodHandle.class,
          HandlerContext.Receiver.class,
          boolean.class,
          String.class,
          boolean.class);
  private static final MethodHandle BINARY =
      LOOKUP.method(
          NativeMethodHandleWrappers.class,
          "onBinary",
          MethodHandle.class,
          ReceiveContexts.class,
          boolean.class,
          ByteBuffer.class,
          boolean.class,
          Callback.class);
  private static final MethodHandle CLOSE =
      LOOKUP.method(
          NativeMethodHandleWrappers.class,
          "onClose",
          MethodHandle.class,
          HandlerContext.Receiver.class,
          Session.class,
          int.class,
          String.class);

  private static HandlerContext.Receiver context(AgentSpan span, CoreSession session) {
    if (Config.get().isWebsocketMessagesInheritSampling()) {
      span.forceSamplingDecision();
    }
    return new HandlerContext.Receiver(
        span.getLocalRootSpan(), Integer.toHexString(System.identityHashCode(session)));
  }

  public static MethodHandle wrapMessage(
      MethodHandle delegate,
      AgentSpan span,
      CoreSession session,
      ContextStore<CoreSession, ReceiveContexts> contextStore) {
    MethodType type = delegate.type();
    Class<?> payload = type.parameterType(0);
    if (payload != String.class && payload != ByteBuffer.class) {
      return delegate;
    }
    boolean partial = type.parameterCount() > 1 && type.parameterType(1) == boolean.class;
    MethodHandle normalized = partial ? delegate : dropArguments(delegate, 1, boolean.class);
    ReceiveContexts contexts = contextStore.get(session);
    if (contexts == null) {
      contexts = contextStore.getOrPut(session, new ReceiveContexts());
    }
    HandlerContext.Receiver context = context(span, session);
    contexts.add(context, payload == String.class);
    MethodHandle wrapper =
        payload == String.class
            ? insertArguments(TEXT, 0, normalized, context, partial)
            : insertArguments(BINARY, 0, normalized, contexts, partial);
    return partial ? wrapper : insertArguments(wrapper, 1, true);
  }

  public static MethodHandle wrapClose(MethodHandle delegate, AgentSpan span, CoreSession session) {
    // Annotated endpoints have a Session argument; listeners do not. Jetty binds it during onOpen.
    if (delegate.type().parameterType(0) != Session.class) {
      delegate = dropArguments(delegate, 0, Session.class);
    }
    return insertArguments(CLOSE, 0, delegate, context(span, session));
  }

  private static ContextScope startMessage(
      HandlerContext.Receiver context, Object data, boolean partial) {
    try {
      synchronized (context) {
        return activateSpan(DECORATE.startInboundFrameSpan(context, data, partial));
      }
    } catch (Throwable t) {
      ExceptionLogger.LOGGER.debug("Unable to start native Jetty WebSocket span", t);
      return null;
    }
  }

  public static void onText(
      MethodHandle delegate,
      HandlerContext.Receiver context,
      boolean partial,
      String payload,
      boolean last)
      throws Throwable {
    boolean finish = last;
    try (ContextScope ignored = startMessage(context, payload, partial)) {
      try {
        delegate.invokeExact(payload, last);
      } catch (Throwable t) {
        finish = true;
        synchronized (context) {
          DECORATE.onError(context.getWebsocketSpan(), t);
        }
        throw t;
      }
    } finally {
      if (finish) {
        synchronized (context) {
          DECORATE.onFrameEnd(context);
        }
      }
    }
  }

  public static void onBinary(
      MethodHandle delegate,
      ReceiveContexts contexts,
      boolean partial,
      ByteBuffer payload,
      boolean last,
      Callback callback)
      throws Throwable {
    ReceiveCallback wrapped;
    ContextScope scope;
    synchronized (contexts) {
      BinaryMessage message = contexts.startBinaryMessage(last);
      scope = startMessage(message, payload, partial);
      wrapped = new ReceiveCallback(callback, contexts, message);
    }
    try (ContextScope ignored = scope) {
      try {
        delegate.invokeExact(payload, last, (Callback) wrapped);
      } catch (Throwable t) {
        wrapped.onFailure(t);
        throw t;
      }
    } finally {
      wrapped.onHandlerExit();
    }
  }

  public static class ReceiveContexts {
    private HandlerContext.Receiver text;
    private HandlerContext.Receiver binary;
    private BinaryMessage currentBinary;
    private final Set<BinaryMessage> pendingBinary = new HashSet<>();

    public void add(HandlerContext.Receiver context, boolean isText) {
      if (isText) {
        text = context;
      } else {
        binary = context;
      }
    }

    public synchronized BinaryMessage startBinaryMessage(boolean last) {
      BinaryMessage message = currentBinary;
      if (message == null) {
        message = new BinaryMessage(binary);
        pendingBinary.add(message);
      }
      message.pendingCallbacks++;
      message.complete = last;
      // Jetty can deliver the next message before this message's callbacks complete.
      currentBinary = last ? null : message;
      return message;
    }

    public synchronized void finish() {
      finish(text);
      for (BinaryMessage message : pendingBinary) {
        finish(message);
      }
      pendingBinary.clear();
      currentBinary = null;
    }

    private static void finish(HandlerContext.Receiver context) {
      if (context != null) {
        synchronized (context) {
          DECORATE.onFrameEnd(context);
        }
      }
    }
  }

  public static class BinaryMessage extends HandlerContext.Receiver {
    private int pendingCallbacks;
    private boolean complete;

    public BinaryMessage(HandlerContext.Receiver context) {
      super(context.getHandshakeSpan(), context.getSessionId());
    }
  }

  public static class ReceiveCallback implements Callback {
    private final Callback delegate;
    private final ReceiveContexts contexts;
    private final BinaryMessage message;
    private boolean completed;
    private boolean handlerExited;
    private boolean released;

    public ReceiveCallback(Callback delegate, ReceiveContexts contexts, BinaryMessage message) {
      this.delegate = delegate;
      this.contexts = contexts;
      this.message = message;
    }

    @Override
    public void succeed() {
      synchronized (contexts) {
        completed = true;
        finish();
      }
      delegate.succeed();
    }

    @Override
    public void fail(Throwable failure) {
      onFailure(failure);
      delegate.fail(failure);
    }

    public void onFailure(Throwable failure) {
      synchronized (contexts) {
        completed = true;
        message.complete = true;
        if (contexts.currentBinary == message) {
          contexts.currentBinary = null;
        }
        if (message.getWebsocketSpan() != null) {
          DECORATE.onError(message.getWebsocketSpan(), failure);
        }
        finish();
      }
    }

    public void onHandlerExit() {
      synchronized (contexts) {
        handlerExited = true;
        finish();
      }
    }

    private void finish() {
      // A synchronous callback may complete before the handler throws or closes its scope.
      if (handlerExited && completed && !released) {
        released = true;
        if (--message.pendingCallbacks == 0 && message.complete) {
          DECORATE.onFrameEnd(message);
          contexts.pendingBinary.remove(message);
        }
      }
    }
  }

  public static void onClose(
      MethodHandle delegate,
      HandlerContext.Receiver context,
      Session session,
      int code,
      String reason)
      throws Throwable {
    ContextScope scope = null;
    try {
      scope = activateSpan(DECORATE.startInboundCloseSpan(context, reason, code));
    } catch (Throwable t) {
      ExceptionLogger.LOGGER.debug("Unable to start native Jetty WebSocket close span", t);
    }
    try (ContextScope ignored = scope) {
      try {
        delegate.invokeExact(session, code, reason);
      } catch (Throwable t) {
        DECORATE.onError(context.getWebsocketSpan(), t);
        throw t;
      }
    } finally {
      DECORATE.onFrameEnd(context);
    }
  }
}
