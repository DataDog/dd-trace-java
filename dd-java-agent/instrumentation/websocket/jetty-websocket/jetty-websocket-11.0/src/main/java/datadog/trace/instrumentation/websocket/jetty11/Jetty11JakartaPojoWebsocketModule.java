package datadog.trace.instrumentation.websocket.jetty11;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.instrumentation.websocket.jetty10.Jetty10JavaxPojoWebSocketModule;
import java.util.HashMap;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class Jetty11JakartaPojoWebsocketModule extends Jetty10JavaxPojoWebSocketModule {

  public Jetty11JakartaPojoWebsocketModule() {
    super("jakarta", "org.eclipse.jetty.websocket.jakarta.common.Jakarta");
  }

  @Override
  public String muzzleDirective() {
    return "jetty-websocket-11";
  }

  @Override
  public Map<String, String> adviceShading() {
    final Map<String, String> map = new HashMap<>();
    map.put("javax", "jakarta");
    map.put(
        "org.eclipse.jetty.websocket.javax.common.Javax",
        "org.eclipse.jetty.websocket.jakarta.common.Jakarta");
    return map;
  }
}
