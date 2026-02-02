package datadog.trace.instrumentation.websocket.org;

import datadog.context.propagation.CarrierSetter;
import org.java_websocket.client.WebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebsocketHeaderInjector implements CarrierSetter<WebSocketClient> {
  public static final WebsocketHeaderInjector SETTER = new WebsocketHeaderInjector();
  private static final Logger log = LoggerFactory.getLogger(WebsocketHeaderInjector.class);

  @Override
  public void set(WebSocketClient carrier, String key, String value) {
    carrier.addHeader(key, value);
  }
}
