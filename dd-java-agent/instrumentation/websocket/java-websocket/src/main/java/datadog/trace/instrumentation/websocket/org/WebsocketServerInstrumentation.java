package datadog.trace.instrumentation.websocket.org;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;
import static datadog.trace.instrumentation.websocket.org.WebSocketServerDecorator.SERVER_DECORATOR;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.Handshakedata;

public class WebsocketServerInstrumentation implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public WebsocketServerInstrumentation() {
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.java_websocket.server.WebSocketServer";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
  }
  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(nameStartsWith("onWebsocketOpen"))
            .and(takesArguments(2))
        ,
        getClass().getName() + "$OnOpenAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(nameStartsWith("onMessage"))
            .and(takesArguments(2))
        ,
        getClass().getName() + "$OnMessageAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(nameStartsWith("onClose"))
            .and(takesArguments(4))
        ,
        getClass().getName() + "$OnCloseAdvice");
  }


  public static class OnOpenAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.Argument(value = 0) WebSocket conn,
        @Advice.Argument(value = 1) Handshakedata handshake) {
      AgentSpan span = SERVER_DECORATOR.open(conn, handshake);
      WebsocketAgentSpanContext spanContext = new WebsocketAgentSpanContext(span.context(),span.getTag(Tags.SPAN_KIND));
      InstrumentationContext.get(WebSocket.class, WebsocketAgentSpanContext.class).put(conn,spanContext);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void onExit(
        @Advice.Enter final AgentScope scope,
        @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }
      SERVER_DECORATOR.onError(scope.span(), throwable);
      SERVER_DECORATOR.beforeFinish(scope.span());

      scope.close();
      scope.span().finish();
    }
  }

  public static class OnMessageAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.Argument(value = 0) WebSocket conn,
        @Advice.Argument(value = 1) Object message ) {
      WebsocketAgentSpanContext context = InstrumentationContext.get(WebSocket.class, WebsocketAgentSpanContext.class).get(conn);
      if (context == null) {
        return activateSpan(noopSpan());
      }
      AgentSpanContext spanContext = context.getSpanContext();
      final AgentSpan wsSpan =
          SERVER_DECORATOR.onMessage(message,spanContext);
      return activateSpan(wsSpan);
    }

      @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
      public static void onExit(
          @Advice.Enter final AgentScope scope,
          @Advice.Thrown final Throwable throwable) {
        if (scope == null) {
          return;
        }
        SERVER_DECORATOR.onError(scope.span(), throwable);
        SERVER_DECORATOR.beforeFinish(scope.span());

        scope.close();
        scope.span().finish();
      }
    }

  public static class OnCloseAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.Argument(value = 0) WebSocket conn,
        @Advice.Argument(value = 1) int code,
        @Advice.Argument(value = 2) String message,
        @Advice.Argument(value = 3) boolean remote) {
      WebsocketAgentSpanContext context = InstrumentationContext.get(WebSocket.class, WebsocketAgentSpanContext.class).get(conn);
      if (context == null) {
        return activateSpan(noopSpan());
      }
      AgentSpanContext spanContext = context.getSpanContext();
      final AgentSpan wsSpan =
          SERVER_DECORATOR.onClose(code,message,remote,spanContext);
      InstrumentationContext.get(WebSocket.class, WebsocketAgentSpanContext.class).remove(conn);
      return activateSpan(wsSpan);
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void onExit(
        @Advice.Enter final AgentScope scope,
        @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }
      SERVER_DECORATOR.onError(scope.span(), throwable);
      SERVER_DECORATOR.beforeFinish(scope.span());
      scope.close();
      scope.span().finish();
    }
  }

}
