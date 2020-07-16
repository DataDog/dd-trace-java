package datadog.trace.instrumentation.grizzly;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import org.glassfish.grizzly.http.server.Request;

public class GrizzlyRequestExtractAdapter implements AgentPropagation.ContextVisitor<Request> {

  public static final GrizzlyRequestExtractAdapter GETTER = new GrizzlyRequestExtractAdapter();

  @Override
  public void forEachKey(Request carrier, AgentPropagation.KeyClassifier classifier) {
    for (String header : carrier.getHeaderNames()) {
      if (!classifier.accept(header, carrier.getHeader(header))) {
        return;
      }
    }
  }
}
