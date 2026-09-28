package datadog.trace.instrumentation.websocket.jetty12;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import java.util.Map;

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
        packageName + ".NativeWebSocketAdvices$OpenAdvice");
    transformer.applyAdvice(
        named("createMessageSink").and(takesArguments(4)),
        packageName + ".NativeWebSocketAdvices$MessageSinkAdvice");
    transformer.applyAdvice(
        named("notifyOnClose").and(takesArguments(2)),
        packageName + ".NativeWebSocketAdvices$CloseAdvice");
  }
}
