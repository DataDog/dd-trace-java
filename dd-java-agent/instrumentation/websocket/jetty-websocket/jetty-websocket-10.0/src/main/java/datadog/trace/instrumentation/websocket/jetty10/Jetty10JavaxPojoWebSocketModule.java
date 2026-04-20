package datadog.trace.instrumentation.websocket.jetty10;

import static datadog.trace.agent.tooling.muzzle.Reference.EXPECTS_NON_STATIC;
import static datadog.trace.agent.tooling.muzzle.Reference.EXPECTS_PUBLIC;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.instrumentation.websocket.HandlerContext;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class Jetty10JavaxPojoWebSocketModule extends InstrumenterModule.Tracing {
  private final String jsrNamespace;
  private final String jettyNamespace;

  public Jetty10JavaxPojoWebSocketModule() {
    this("javax", "org.eclipse.jetty.websocket.javax.common.Javax");
  }

  protected Jetty10JavaxPojoWebSocketModule(
      final String jsrNamespace, final String jettyNamespace) {
    super("jetty", "jetty-websocket", jsrNamespace + "-websocket", "websocket");
    this.jsrNamespace = jsrNamespace;
    this.jettyNamespace = jettyNamespace;
  }

  @Override
  protected boolean defaultEnabled() {
    return InstrumenterConfig.get().isWebsocketTracingEnabled();
  }

  @Override
  public Map<String, String> contextStore() {
    final Map<String, String> map = new HashMap<>();
    map.put(jettyNamespace + "WebSocketSession", Boolean.class.getName());
    map.put(jsrNamespace + ".websocket.Session", HandlerContext.Sender.class.getName());
    return map;
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {
      new Reference.Builder(jettyNamespace + "WebSocketMessageMetadata")
          .withMethod(
              new String[0],
              EXPECTS_NON_STATIC | EXPECTS_PUBLIC,
              "getMethodHandle",
              "Ljava/lang/invoke/MethodHandle;")
          .build(),
    };
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.websocket.jetty10.MethodHandleWrappers",
    };
  }

  @Override
  public String muzzleDirective() {
    return "jetty-websocket-10";
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return Arrays.asList(
        new JavaxWebSocketFrameHandlerFactoryInstrumentation(jettyNamespace),
        new JavaxWebSocketFrameHandlerInstrumentation(jettyNamespace));
  }
}
