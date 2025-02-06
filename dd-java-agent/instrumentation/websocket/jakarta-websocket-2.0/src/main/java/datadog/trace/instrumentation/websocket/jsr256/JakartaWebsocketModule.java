package datadog.trace.instrumentation.websocket.jsr256;

import static java.util.Collections.singletonMap;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.HashMap;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class JakartaWebsocketModule extends JavaxWebsocketModule {

  public JakartaWebsocketModule() {
    super("jakarta", "jakarta-websocket", "websocket");
  }

  @Override
  public Map<String, String> adviceShading() {
    return singletonMap("javax", "jakarta");
  }

  @Override
  public Map<String, String> contextStore() {
    final Map<String, String> map = new HashMap<>();
    map.put("jakarta.websocket.Session", AgentSpan.class.getName());
    map.put("jakarta.websocket.RemoteEndpoint", packageName + ".shaded.HandlerContext$Sender");
    map.put("jakarta.websocket.MessageHandler", packageName + ".shaded.HandlerContext$Receiver");
    return map;
  }

  @Override
  public String muzzleDirective() {
    return "jakarta-websocket";
  }
}
