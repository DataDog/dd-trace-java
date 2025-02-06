package datadog.trace.instrumentation.websocket.jsr256;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.websocket.jsr256.WebsocketDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import javax.websocket.CloseReason;
import javax.websocket.Session;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class EndpointInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  private final String namespace;

  public EndpointInstrumentation(String namespace) {
    this.namespace = namespace;
  }

  @Override
  public String hierarchyMarkerType() {
    return namespace + ".websocket.Endpoint";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isPublic()
            .and(
                named("onOpen")
                    .and(takesArguments(2))
                    .and(takesArgument(0, named(namespace + ".websocket.Session")))),
        getClass().getName() + "$CaptureHandshakeSpanAdvice");
    transformer.applyAdvice(
        isPublic()
            .and(
                named("onClose")
                    .and(takesArguments(2))
                    .and(takesArgument(0, named(namespace + ".websocket.Session")))
                    .and(takesArgument(1, named(namespace + ".websocket.CloseReason")))),
        getClass().getName() + "$SessionCloseAdvice");
  }

  public static class CaptureHandshakeSpanAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) final Session session) {
      final AgentSpan current = AgentTracer.get().activeSpan();
      if (current != null) {
        InstrumentationContext.get(Session.class, AgentSpan.class)
            .putIfAbsent(session, current.getLocalRootSpan());
      }
    }
  }

  public static class SessionCloseAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.Local("handlerContext") HandlerContext.Receiver handlerContext,
        @Advice.Argument(0) final Session session,
        @Advice.Argument(1) final CloseReason closeReason) {
      final AgentSpan handshakeSpan =
          InstrumentationContext.get(Session.class, AgentSpan.class).remove(session);
      if (handshakeSpan == null) {
        return null;
      }
      handlerContext = new HandlerContext.Receiver(handshakeSpan, session.getId());

      return activateSpan(
          DECORATE.onSessionCloseReceived(
              handlerContext, closeReason.getReasonPhrase(), closeReason.getCloseCode().getCode()));
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onEnter(@Advice.Enter final AgentScope scope) {
      if (scope != null) {
        final AgentSpan span = scope.span();
        // decorate also here
        span.finish();
        scope.close();
      }
    }
  }
}
