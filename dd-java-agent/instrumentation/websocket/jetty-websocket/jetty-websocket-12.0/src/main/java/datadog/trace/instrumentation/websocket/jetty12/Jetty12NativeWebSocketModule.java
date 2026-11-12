package datadog.trace.instrumentation.websocket.jetty12;

import static java.util.Arrays.asList;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class Jetty12NativeWebSocketModule extends InstrumenterModule.Tracing {
  public Jetty12NativeWebSocketModule() {
    super("jetty", "jetty-websocket", "websocket");
  }

  @Override
  protected boolean defaultEnabled() {
    return InstrumenterConfig.get().isWebsocketTracingEnabled();
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return asList(
        new Jetty12NativeFrameHandlerInstrumentation(), new Jetty12NativeSessionInstrumentation());
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
      packageName + ".NativeMethodHandleWrappers$ReceiveCallback",
      packageName + ".NativeSendContext",
      packageName + ".NativeSendContext$Message",
      packageName + ".NativeSendContext$SendCallback"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> stores = new HashMap<>();
    stores.put(
        "org.eclipse.jetty.websocket.core.CoreSession",
        packageName + ".NativeMethodHandleWrappers$ReceiveContexts");
    stores.put(
        "org.eclipse.jetty.websocket.common.WebSocketSession", packageName + ".NativeSendContext");
    return stores;
  }
}
