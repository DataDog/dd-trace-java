package datadog.trace.instrumentation.websocket.jetty12;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.websocket.jetty12.NativeSendContext.SendCallback;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.common.WebSocketSession;

public class NativeSessionInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.websocket.common.WebSocketSession";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$ConstructAdvice");
    transformer.applyAdvice(
        namedOneOf("sendText", "sendBinary")
            .and(takesArguments(2))
            .and(takesArgument(1, named("org.eclipse.jetty.websocket.api.Callback"))),
        getClass().getName() + "$SendAdvice");
    transformer.applyAdvice(
        namedOneOf("sendPartialText", "sendPartialBinary")
            .and(takesArguments(3))
            .and(takesArgument(1, boolean.class))
            .and(takesArgument(2, named("org.eclipse.jetty.websocket.api.Callback"))),
        getClass().getName() + "$PartialSendAdvice");
  }

  public static class ConstructAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void after(@Advice.This WebSocketSession session) {
      AgentSpan span = activeSpan();
      if (span != null) {
        InstrumentationContext.get(WebSocketSession.class, NativeSendContext.class)
            .put(session, new NativeSendContext(span, session.getCoreSession()));
      }
    }
  }

  public static class SendAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope before(
        @Advice.This WebSocketSession session,
        @Advice.Origin("#m") String method,
        @Advice.Argument(0) Object payload,
        @Advice.Argument(value = 1, readOnly = false) Callback callback,
        @Advice.Local("send") SendCallback send) {
      NativeSendContext context =
          InstrumentationContext.get(WebSocketSession.class, NativeSendContext.class).get(session);
      if (context == null) {
        return null;
      }
      send = context.start(payload, "sendBinary".equals(method), false, true, callback);
      if (send == null) {
        return null;
      }
      callback = send;
      return activateSpan(send.span());
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void after(
        @Advice.Enter ContextScope scope,
        @Advice.Local("send") SendCallback send,
        @Advice.Thrown Throwable failure) {
      if (send != null) {
        send.onMethodExit(scope, failure);
      }
    }
  }

  public static class PartialSendAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope before(
        @Advice.This WebSocketSession session,
        @Advice.Origin("#m") String method,
        @Advice.Argument(0) Object payload,
        @Advice.Argument(1) boolean last,
        @Advice.Argument(value = 2, readOnly = false) Callback callback,
        @Advice.Local("send") SendCallback send) {
      NativeSendContext context =
          InstrumentationContext.get(WebSocketSession.class, NativeSendContext.class).get(session);
      if (context == null) {
        return null;
      }
      boolean binary = "sendPartialBinary".equals(method);
      send = context.start(payload, binary, true, last, callback);
      if (send == null) {
        return null;
      }
      // Jetty accepts null callbacks for binary sends, but partial text sends throw.
      if (callback != null || binary) {
        callback = send;
      }
      return activateSpan(send.span());
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void after(
        @Advice.Enter ContextScope scope,
        @Advice.Local("send") SendCallback send,
        @Advice.Thrown Throwable failure) {
      if (send != null) {
        send.onMethodExit(scope, failure);
      }
    }
  }
}
