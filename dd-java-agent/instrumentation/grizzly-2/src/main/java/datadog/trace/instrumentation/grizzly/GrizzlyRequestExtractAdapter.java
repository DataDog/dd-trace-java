package datadog.trace.instrumentation.grizzly;

import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.KeyClassifier.IGNORE;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.CachingContextVisitor;
import org.glassfish.grizzly.http.server.Request;

public class GrizzlyRequestExtractAdapter extends CachingContextVisitor<Request> {

  public static final GrizzlyRequestExtractAdapter GETTER = new GrizzlyRequestExtractAdapter();

  @Override
  public void forEachKey(
      Request carrier,
      AgentPropagation.KeyClassifier classifier,
      AgentPropagation.KeyValueConsumer consumer) {
    for (String header : carrier.getHeaderNames()) {
      String lowerCaseKey = toLowerCase(header);
      int classification = classifier.classify(lowerCaseKey);
      if (classification != IGNORE) {
        if (!consumer.accept(classification, lowerCaseKey, carrier.getHeader(header))) {
          return;
        }
      }
    }
  }
}
