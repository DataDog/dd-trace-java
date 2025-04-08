package datadog.trace.instrumentation.websocket.jetty10;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.WebsocketDecorator.DECORATE;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.ExceptionLogger;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.websocket.HandlerContext;
import datadog.trace.util.MethodHandles;
import java.lang.invoke.MethodHandle;
import javax.websocket.CloseReason;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.Session;
import org.eclipse.jetty.websocket.javax.common.JavaxWebSocketSession;

/**
 * This class contains advices for method annotated with OnOpen, OnClose and OnMessage. Unlikely
 * other instrumentation, jetty is using method handles to do direct call to these methods. For this
 * reason, we need to swap the original handle with those allowing to instrument around method
 * invocations. Jetty is also doing argument permutations that ensure that arguments will come in
 * the expected order. Jetty is also always bind the Session as argument that is required to look up
 * the handshake span. Instrumenting directly @OnMessage methods won't have in all case guarantee to
 * have that information since the Session parameter is optional.
 */
public class MethodHandleWrappers {
  /**
   * OnOpen method handles that takes more arguments comparing to the original. We inject at the
   * beginning the original to delegate the call, the context stores we need to lookup the handler
   * context and the original parameter jetty is already binding (the session and the endpoint
   * config)
   */
  public static final MethodHandle OPEN_METHOD_HANDLE =
      new MethodHandles(MethodHandleWrappers.class.getClassLoader())
          .method(
              MethodHandleWrappers.class,
              "onOpen",
              MethodHandle.class,
              ContextStore.class,
              ContextStore.class,
              JavaxWebSocketSession.class,
              EndpointConfig.class);

  /**
   * OnClose method handles that takes more arguments comparing to the original. We inject at the
   * beginning the origin alto delegate the call, the context stores we need to lookup the handler
   * context and the original parameter jetty is already binding (the session and the close reason)
   */
  public static final MethodHandle CLOSE_METHOD_HANDLE =
      new MethodHandles(MethodHandleWrappers.class.getClassLoader())
          .method(
              MethodHandleWrappers.class,
              "onClose",
              MethodHandle.class,
              ContextStore.class,
              Session.class,
              CloseReason.class);

  /**
   * OnMessage method handles that takes more arguments comparing to the original. We inject at the
   * beginning the origin alto delegate the call, the context stores we need to lookup the handler
   * context, the session and the receiver handler context we create when the jetty message sink are
   * instrumented. At the end we also accept the original method arguments as an object varargs
   * since we cannot know in advance the one that will be passed.
   */
  public static final MethodHandle MESSAGE_METHOD_HANDLE =
      new MethodHandles(MethodHandleWrappers.class.getClassLoader())
          .method(
              MethodHandleWrappers.class,
              "onMessage",
              MethodHandle.class,
              JavaxWebSocketSession.class,
              HandlerContext.Receiver.class,
              ContextStore.class,
              Object[].class);

  public static void onOpen(
      MethodHandle delegate,
      ContextStore<Session, HandlerContext.Sender> sessionStore,
      ContextStore<JavaxWebSocketSession, Boolean> specificStore,
      JavaxWebSocketSession session,
      EndpointConfig config)
      throws Throwable {
    try {
      specificStore.put(session, true);

      final AgentSpan current = AgentTracer.get().activeSpan();

      if (current != null) {
        // we need to force the sampling decision in case the span is linked
        if (Config.get().isWebsocketMessagesInheritSampling()) {
          current.forceSamplingDecision();
        }
        sessionStore.putIfAbsent(
            session, new HandlerContext.Sender(current.getLocalRootSpan(), session.getId()));
      }
    } catch (Throwable t) {
      ExceptionLogger.LOGGER.debug("Unforeseen error instrumenting jetty websocket POJO", t);
    } finally {
      delegate.invoke(session, config);
    }
  }

  public static void onClose(
      MethodHandle delegate,
      ContextStore<Session, HandlerContext.Sender> contextStore,
      Session session,
      CloseReason closeReason)
      throws Throwable {
    HandlerContext.Sender handlerContext =
        contextStore != null && session != null ? contextStore.get(session) : null;
    if (handlerContext != null) {
      final HandlerContext.Receiver closeContext =
          new HandlerContext.Receiver(handlerContext.getHandshakeSpan(), session.getId());
      try (AgentScope ignored =
          activateSpan(
              DECORATE.onSessionCloseReceived(
                  closeContext,
                  closeReason.getReasonPhrase(),
                  closeReason.getCloseCode().getCode()))) {
        delegate.invoke(session, closeReason);
      } finally {
        DECORATE.onFrameEnd(closeContext);
      }
    } else {
      delegate.invoke(session, closeReason);
    }
  }

  public static Object onMessage(
      MethodHandle delegate,
      JavaxWebSocketSession session,
      HandlerContext.Receiver handlerContext,
      ContextStore<JavaxWebSocketSession, Boolean> contextStore,
      Object[] args)
      throws Throwable {
    AgentSpan wsSpan = null;
    if (!Boolean.TRUE.equals(contextStore.get(session))) {
      return delegate.invokeWithArguments(args);
    }
    boolean instrument;
    boolean finishSpan = true;
    try {
      instrument =
          handlerContext != null
              && args != null
              && args.length > 0
              && CallDepthThreadLocalMap.incrementCallDepth(MessageHandler.class) == 0;
      boolean partialDelivery;
      if (instrument) {
        partialDelivery = args.length > 1 && (args[1] instanceof Boolean);
        if (partialDelivery) {
          finishSpan = (boolean) args[1];
        }
        wsSpan = DECORATE.onReceiveFrameStart(handlerContext, args[0], partialDelivery);
      }
    } catch (Throwable t) {
      ExceptionLogger.LOGGER.debug("Unforeseen error instrumenting jetty websocket POJO", t);
    }
    if (wsSpan != null) {
      try (AgentScope ignored = activateSpan(wsSpan)) {
        return delegate.invokeWithArguments(args);
      } catch (Throwable t) {
        finishSpan = true;
        DECORATE.onError(wsSpan, t);

        throw t;
      } finally {
        CallDepthThreadLocalMap.reset(MessageHandler.class);
        if (finishSpan) {
          DECORATE.onFrameEnd(handlerContext);
        }
      }
    }
    return delegate.invokeWithArguments(args);
  }
}
