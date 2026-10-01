package datadog.trace.instrumentation.websocket.org;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.util.Iterator;
import org.java_websocket.handshake.Handshakedata;

public class WebsocketExtractAdapter implements AgentPropagation.ContextVisitor<Handshakedata> {
  public static final WebsocketExtractAdapter GETTER = new WebsocketExtractAdapter();

  @Override
  public void forEachKey(Handshakedata carrier, AgentPropagation.KeyClassifier classifier) {
    Iterator<String> iterator = carrier.iterateHttpFields();
    while (iterator.hasNext()) {
      String key = iterator.next();
      String value = carrier.getFieldValue(key);
      if (null != value) {
        if (!classifier.accept(key, value)) {
          return;
        }
      }
    }
  }
}
