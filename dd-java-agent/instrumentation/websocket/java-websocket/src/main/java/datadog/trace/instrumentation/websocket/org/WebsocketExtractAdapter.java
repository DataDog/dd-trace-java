package datadog.trace.instrumentation.websocket.org;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.util.Iterator;
import org.java_websocket.handshake.Handshakedata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebsocketExtractAdapter implements AgentPropagation.ContextVisitor<Handshakedata>{
  public static final WebsocketExtractAdapter GETTER = new WebsocketExtractAdapter();
  private static final Logger log = LoggerFactory.getLogger(WebsocketExtractAdapter.class);
  @Override
  public void forEachKey(Handshakedata carrier, AgentPropagation.KeyClassifier classifier) {
    Iterator<String> iterator = carrier.iterateHttpFields();
    while(iterator.hasNext()){
      String key = iterator.next();
      String value = carrier.getFieldValue(key);
      if (log.isDebugEnabled()) {
        log.info("websocket ==== key:{},value:{}", key, value);
      }
      if (null != value) {
        if (!classifier.accept(key, value)) {
          return;
        }
      }
    }
  }
}
