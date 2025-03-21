package datadog.trace.instrumentation.websocket.jetty12;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.instrumentation.websocket.jetty10.Jetty10JavaxPojoWebSocketModule;
import java.util.HashMap;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class Jetty12EE9JakartaPojoWebsocketModule extends Jetty10JavaxPojoWebSocketModule {

  public Jetty12EE9JakartaPojoWebsocketModule() {
    super("jakarta", "org.eclipse.jetty.ee9.websocket.jakarta.common.Jakarta");
  }

  @Override
  public String muzzleDirective() {
    return "jetty-websocket-12e9";
  }

  @Override
  public Map<String, String> adviceShading() {
    final Map<String, String> ret = new HashMap<>();
    ret.put("javax", "jakarta");
    ret.put(
        "org.eclipse.jetty.websocket.javax.common.Javax",
        "org.eclipse.jetty.ee9.websocket.jakarta.common.Jakarta");
    return ret;
  }
}
