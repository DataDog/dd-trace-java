package datadog.trace.instrumentation.websocket.org;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebsocketHeaderExtract implements AgentPropagation.ContextVisitor<Map<String, String>>{
  public static final WebsocketHeaderExtract HEADER_GETTER = new WebsocketHeaderExtract();
  private static final Logger log = LoggerFactory.getLogger(WebsocketHeaderExtract.class);
  @Override
  public void forEachKey(Map<String, String> carrier, AgentPropagation.KeyClassifier classifier) {
    for (Map.Entry<String, String> entry : carrier.entrySet()){
      if (!classifier.accept(entry.getKey(), entry.getValue())) {
        return;
      }
    }
  }
}
