package datadog.trace.instrumentation.websocket.org;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;
import static datadog.trace.instrumentation.websocket.org.WebSocketServerDecorator.SERVER_DECORATOR;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPrivate;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Collection;
import net.bytebuddy.asm.Advice;
import org.java_websocket.WebSocket;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.framing.Framedata;
import org.java_websocket.framing.PingFrame;
import org.java_websocket.framing.PongFrame;


public class WebSocketSendInstrumentation implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public WebSocketSendInstrumentation() {
  }

  @Override
  public String instrumentedType() {
    return "org.java_websocket.WebSocketImpl";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPrivate())
            .and(named("send"))
            .and(takesArguments(1))
            .and(takesArgument(0, Collection.class))
        ,
        getClass().getName() + "$OnSendAdvice");
  }


  public static class OnSendAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.This WebSocketImpl impl,
        @Advice.Argument(value = 0) Collection<Framedata> frames) {
      WebsocketAgentSpanContext context = InstrumentationContext.get(WebSocket.class, WebsocketAgentSpanContext.class).get(impl);
      if (context == null) {
        // close after send
        final AgentSpan wsSpan =
            SERVER_DECORATOR.send(null);
        return activateSpan(wsSpan);
      }
      // ignore ping/pong/close
      for (Framedata frame : frames) {
        if (frame instanceof PingFrame || frame instanceof PongFrame || frame instanceof CloseFrame){
          return activateSpan(noopSpan());
        }
      }
      final AgentSpan wsSpan =
          SERVER_DECORATOR.send(context);
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
