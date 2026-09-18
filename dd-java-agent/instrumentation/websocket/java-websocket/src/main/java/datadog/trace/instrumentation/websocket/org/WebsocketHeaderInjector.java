package datadog.trace.instrumentation.websocket.org;

import datadog.context.propagation.CarrierSetter;
import org.java_websocket.client.WebSocketClient;

public class WebsocketHeaderInjector implements CarrierSetter<WebSocketClient> {
  public static final WebsocketHeaderInjector SETTER = new WebsocketHeaderInjector();

  @Override
  public void set(WebSocketClient carrier, String key, String value) {
    carrier.addHeader(key, value);
  }
}
