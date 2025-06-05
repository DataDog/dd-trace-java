package datadog.trace.instrumentation.websocket.jsr256;

import static java.util.Collections.singletonMap;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;
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
  public String muzzleDirective() {
    return "jakarta-websocket";
  }
}
