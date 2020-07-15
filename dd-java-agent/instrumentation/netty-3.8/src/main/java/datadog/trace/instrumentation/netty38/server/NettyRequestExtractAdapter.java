package datadog.trace.instrumentation.netty38.server;

import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.KeyClassifier.IGNORE;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.util.Map;
import org.jboss.netty.handler.codec.http.HttpHeaders;

public class NettyRequestExtractAdapter implements AgentPropagation.ContextVisitor<HttpHeaders> {

  public static final NettyRequestExtractAdapter GETTER = new NettyRequestExtractAdapter();

  @Override
  public void forEachKey(
      HttpHeaders carrier,
      AgentPropagation.KeyClassifier classifier,
      AgentPropagation.KeyValueConsumer consumer) {
    for (Map.Entry<String, String> header : carrier) {
      String lowerCaseKey = header.getKey().toLowerCase();
      int classification = classifier.classify(lowerCaseKey);
      if (classification != IGNORE) {
        if (!consumer.accept(classification, lowerCaseKey, header.getValue())) {
          return;
        }
      }
    }
  }
}
