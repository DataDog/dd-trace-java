package datadog.trace.instrumentation.websocket.jetty10;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
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
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.websocket.jetty10.SyntheticEndpoint",
    };
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return Arrays.asList(
        new JavaxWebSocketFrameHandlerFactoryInstrumentation(jettyNamespace),
        new JavaxWebSocketFrameHandlerInstrumentation(jettyNamespace));
  }
}
