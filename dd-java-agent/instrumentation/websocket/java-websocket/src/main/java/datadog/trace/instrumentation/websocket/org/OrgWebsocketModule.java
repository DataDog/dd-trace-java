package datadog.trace.instrumentation.websocket.org;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class OrgWebsocketModule extends InstrumenterModule.Tracing {

  public OrgWebsocketModule() {
    this("java-websocket", "websocket");
  }

  protected OrgWebsocketModule(String instrumentationName, String... additionalNames) {
    super(instrumentationName, additionalNames);
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStores = new HashMap<>();
    contextStores.put("org.java_websocket.WebSocket", WebsocketAgentSpanContext.class.getName());
    contextStores.put("org.java_websocket.client.WebSocketClient", AgentSpan.class.getName());
    return contextStores;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
        packageName + ".WebSocketDecorator",
        packageName + ".WebSocketClientDecorator",
        packageName + ".WebSocketServerDecorator",
        packageName + ".WebsocketExtractAdapter",
        packageName + ".TraceDraft_6455",
        packageName + ".WebsocketHeaderInjector",
        packageName + ".WebsocketHeaderExtract",
        packageName + ".WebsocketAgentSpanContext",

    };
  }

  @Override
  protected boolean defaultEnabled() {
    return InstrumenterConfig.get().isWebsocketTracingEnabled();
  }

  @Override
  public String muzzleDirective() {
    return "javax-websocket";
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return Arrays.asList(
        new WebsocketClientInstrumentation(),
        new WebsocketServerInstrumentation(),
        new WebSocketSendInstrumentation()
        );
  }
}
