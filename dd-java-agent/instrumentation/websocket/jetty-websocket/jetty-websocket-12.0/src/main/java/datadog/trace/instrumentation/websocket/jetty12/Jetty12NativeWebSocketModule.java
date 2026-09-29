package datadog.trace.instrumentation.websocket.jetty12;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.websocket.jetty12.NativeMethodHandleWrappers.ReceiveContexts;
import java.lang.invoke.MethodHandle;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.core.Behavior;
import org.eclipse.jetty.websocket.core.CoreSession;

@AutoService(InstrumenterModule.class)
public class Jetty12NativeWebSocketModule extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public Jetty12NativeWebSocketModule() {
    super("jetty", "jetty-websocket", "websocket");
  }

  @Override
  protected boolean defaultEnabled() {
    return InstrumenterConfig.get().isWebsocketTracingEnabled();
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.websocket.common.JettyWebSocketFrameHandler";
  }

  @Override
  public String muzzleDirective() {
    return "jetty-websocket-12-native";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".NativeMethodHandleWrappers",
      packageName + ".NativeMethodHandleWrappers$ReceiveContexts",
      packageName + ".NativeMethodHandleWrappers$BinaryMessage",
      packageName + ".NativeMethodHandleWrappers$ReceiveCallback"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(
        "org.eclipse.jetty.websocket.core.CoreSession",
        packageName + ".NativeMethodHandleWrappers$ReceiveContexts");
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
