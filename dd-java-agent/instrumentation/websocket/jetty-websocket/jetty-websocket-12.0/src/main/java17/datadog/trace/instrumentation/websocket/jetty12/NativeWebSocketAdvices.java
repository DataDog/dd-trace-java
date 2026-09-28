package datadog.trace.instrumentation.websocket.jetty12;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.websocket.jetty12.NativeMethodHandleWrappers.ReceiveContexts;
import java.lang.invoke.MethodHandle;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.core.Behavior;
import org.eclipse.jetty.websocket.core.CoreSession;

public class NativeWebSocketAdvices {
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
  }

  public static class MessageSinkAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.Argument(1) WebSocketSession session,
        @Advice.Argument(value = 2, readOnly = false) MethodHandle handle) {
      AgentSpan span = activeSpan();
      if (span != null
          && session.getCoreSession().getBehavior() == Behavior.SERVER
          && handle != null) {
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
