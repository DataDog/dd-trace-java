package datadog.trace.instrumentation.websocket.jetty12;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.instrumentation.websocket.jetty10.Jetty10JavaxPojoWebSocketModule;
import java.util.Collections;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class Jetty12EE8JavaxPojoWebsocketModule extends Jetty10JavaxPojoWebSocketModule {

  public Jetty12EE8JavaxPojoWebsocketModule() {
    super("javax", "org.eclipse.jetty.ee8.websocket.javax.common.Javax");
  }

  @Override
  public String muzzleDirective() {
    return "jetty-websocket-12ee8";
  }

  @Override
  public Map<String, String> adviceShading() {
    return Collections.singletonMap(
        "org.eclipse.jetty.websocket.javax.common.Javax",
        "org.eclipse.jetty.ee8.websocket.javax.common.Javax");
  }
}
