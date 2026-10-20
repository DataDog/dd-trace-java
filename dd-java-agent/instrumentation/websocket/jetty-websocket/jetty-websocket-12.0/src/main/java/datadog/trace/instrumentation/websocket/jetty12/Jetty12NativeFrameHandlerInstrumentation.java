package datadog.trace.instrumentation.websocket.jetty12;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static org.eclipse.jetty.websocket.core.util.InvokerUtils.bindTo;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.websocket.jetty12.NativeMethodHandleWrappers.ReceiveContexts;
import java.lang.invoke.MethodHandle;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.core.Behavior;
import org.eclipse.jetty.websocket.core.CoreSession;

public class Jetty12NativeFrameHandlerInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.websocket.common.JettyWebSocketFrameHandler";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("onOpen")
            .and(takesArguments(2))
            .and(takesArgument(0, named("org.eclipse.jetty.websocket.core.CoreSession"))),
        getClass().getName() + "$OpenAdvice");
    transformer.applyAdvice(
        named("createMessageSink").and(takesArguments(4)),
        getClass().getName() + "$MessageSinkAdvice");
    transformer.applyAdvice(
        named("notifyOnClose").and(takesArguments(2)), getClass().getName() + "$CloseAdvice");
  }

  public static class OpenAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.Argument(0) CoreSession session,
        @Advice.FieldValue(value = "closeHandle", readOnly = false) MethodHandle closeHandle) {
      AgentSpan span = activeSpan();
      if (span != null && session.getBehavior() == Behavior.SERVER && closeHandle != null) {
        closeHandle = NativeMethodHandleWrappers.wrapClose(closeHandle, span, session);
      }
    }

    /**
     * Rejects the native module on Jetty 12.1. Without this check, {@code
     * Jetty12NativeSessionInstrumentation} installs, but {@code
     * Jetty12NativeFrameHandlerInstrumentation} fails due to incompatible {@code MethodHolder}
     * signatures. The missing close cleanup leaves spans for successful partial sends with {@code
     * last=false} unfinished when the connection closes.
     */
    private static void muzzleCheck(MethodHandle handle, Object receiver) {
      bindTo(handle, receiver);
    }
  }

  public static class MessageSinkAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.Argument(1) WebSocketSession session,
        @Advice.Argument(value = 2, readOnly = false) MethodHandle handle) {
      AgentSpan span = activeSpan();
      if (span != null && handle != null) {
        handle =
            NativeMethodHandleWrappers.wrapMessage(
                handle,
                span,
                session.getCoreSession(),
                InstrumentationContext.get(CoreSession.class, ReceiveContexts.class));
      }
    }
  }

  public static class CloseAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.FieldValue("session") WebSocketSession session) {
      if (session != null) {
        NativeSendContext sends =
            InstrumentationContext.get(WebSocketSession.class, NativeSendContext.class)
                .remove(session);
        if (sends != null) {
          sends.finish();
        }
        ReceiveContexts contexts =
            InstrumentationContext.get(CoreSession.class, ReceiveContexts.class)
                .remove(session.getCoreSession());
        if (contexts != null) {
          contexts.finish();
        }
      }
    }
  }
}
