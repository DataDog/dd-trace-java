package datadog.trace.instrumentation.websocket.jsr256;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.WebsocketDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.websocket.HandlerContext;
import javax.websocket.CloseReason;
import javax.websocket.MessageHandler;
import javax.websocket.RemoteEndpoint;
import javax.websocket.Session;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class SessionInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  private final String namespace;

  public SessionInstrumentation(String namespace) {
    this.namespace = namespace;
  }

  @Override
  public String hierarchyMarkerType() {
    return namespace + ".websocket.Session";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isPublic()
            .and(
                named("addMessageHandler")
                    .and(takesArgument(0, named(namespace + ".websocket.MessageHandler")))),
        getClass().getName() + "$LinkReceiverSessionArg0Advice");

    transformer.applyAdvice(
        isPublic()
            .and(
                named("addMessageHandler")
                    .and(
                        takesArgument(
                            1,
                            namedOneOf(
                                namespace + ".websocket.MessageHandler$Whole",
                                namespace + ".websocket.MessageHandler$Partial")))),
        getClass().getName() + "$LinkReceiverSessionArg1Advice");

    transformer.applyAdvice(
        isPublic().and(namedOneOf("getBasicRemote", "getAsyncRemote")).and(takesNoArguments()),
        getClass().getName() + "$LinkSenderSessionAdvice");

    transformer.applyAdvice(
        isPublic()
            .and(named("close"))
            .and(
                takesArguments(1)
                    .and(takesArgument(0, named(namespace + ".websocket.CloseReason")))),
        getClass().getName() + "$SessionCloseAdvice");

    transformer.applyAdvice(
        named("close").and(takesNoArguments()),
        getClass().getName() + "$DefaultSessionCloseAdvice");
  }

  public static class LinkReceiverSessionArg0Advice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.This final Session session, @Advice.Argument(0) final MessageHandler handler) {
      if (handler != null) {
        final HandlerContext.Sender sessionState =
            InstrumentationContext.get(Session.class, HandlerContext.Sender.class).get(session);
        if (sessionState != null) {
          // If the user is adding singletons that is not going to work. However, in this case there
          // is no chance to have the session linked in any way (even if wrapping the
          // messagehandler).
          // Now hopefully this is not the usual habit and implementations of that API are not doing
          // that as well when creating proxies for the annotated pojos.
          // Also, adding a field here is preferred than wrapping the message handler since the
          // implementations are introspecting it to understand the type of message handled and,
          // with
          // erasures, it won't work.
          InstrumentationContext.get(MessageHandler.class, HandlerContext.Receiver.class)
              .put(
                  handler,
                  new HandlerContext.Receiver(sessionState.getHandshakeSpan(), session.getId()));
        }
      }
    }
  }

  public static class LinkReceiverSessionArg1Advice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.This final Session session, @Advice.Argument(1) final MessageHandler handler) {
      if (handler != null) {
        final HandlerContext.Sender sessionState =
            InstrumentationContext.get(Session.class, HandlerContext.Sender.class).get(session);
        if (sessionState != null) {
          // If the user is adding singletons that is not going to work. However, in this case there
          // is no chance to have the session linked in any way (even if wrapping the
          // messagehandler).
          // Now hopefully this is not the usual habit and implementations of that API are not doing
          // that as well when creating proxies for the annotated pojos.
          // Also, adding a field here is preferred than wrapping the message handler since the
          // implementations are introspecting it to understand the type of message handled and,
          // with
          // erasures, it won't work.
          InstrumentationContext.get(MessageHandler.class, HandlerContext.Receiver.class)
              .put(
                  handler,
                  new HandlerContext.Receiver(sessionState.getHandshakeSpan(), session.getId()));
        }
      }
    }
  }

  public static class LinkSenderSessionAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.This final Session session, @Advice.Return final RemoteEndpoint remoteEndpoint) {
      if (remoteEndpoint != null) {
        final HandlerContext.Sender sessionState =
            InstrumentationContext.get(Session.class, HandlerContext.Sender.class).get(session);
        if (sessionState != null) {
          InstrumentationContext.get(RemoteEndpoint.class, HandlerContext.Sender.class)
              .put(remoteEndpoint, sessionState);
        }
      }
    }
  }

  public static class SessionCloseAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope before(
        @Advice.This final Session session,
        @Advice.Argument(0) final CloseReason reason,
        @Advice.Local("handlerContext") HandlerContext.Sender handlerContext) {
      handlerContext =
          InstrumentationContext.get(Session.class, HandlerContext.Sender.class).remove(session);
      if (handlerContext == null) {
        return null;
      }
      return activateSpan(
          DECORATE.onSessionCloseIssued(
              handlerContext, reason.getReasonPhrase(), reason.getCloseCode().getCode()));
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(
        @Advice.Enter final AgentScope scope,
        @Advice.Thrown final Throwable thrown,
        @Advice.Local("handlerContext") HandlerContext.Sender handlerContext) {
      if (scope != null) {
        DECORATE.onError(scope, thrown);
        DECORATE.onFrameEnd(handlerContext);
        scope.close();
      }
    }
  }

  public static class DefaultSessionCloseAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope before(
        @Advice.This final Session session,
        @Advice.Local("handlerContext") HandlerContext.Sender handlerContext) {

      handlerContext =
          InstrumentationContext.get(Session.class, HandlerContext.Sender.class).remove(session);
      if (handlerContext == null) {
        return null;
      }
      return activateSpan(DECORATE.onSessionCloseIssued(handlerContext, null, 1000));
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(
        @Advice.Enter final AgentScope scope,
        @Advice.Thrown final Throwable thrown,
        @Advice.Local("handlerContext") HandlerContext.Sender handlerContext) {
      if (scope != null) {
        DECORATE.onError(scope, thrown);
        DECORATE.onFrameEnd(handlerContext);
        scope.close();
      }
    }
  }
}
