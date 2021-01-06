package datadog.trace.instrumentation.jetty76;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.util.Enumeration;
import org.eclipse.jetty.server.Request;

public class RequestExtractAdapter implements AgentPropagation.ContextVisitor<Request> {

  public static final RequestExtractAdapter GETTER = new RequestExtractAdapter();

  @Override
  public void forEachKey(Request carrier, AgentPropagation.KeyClassifier classifier) {
    Enumeration<String> headerNames = carrier.getHeaderNames();
    while (headerNames.hasMoreElements()) {
      String header = headerNames.nextElement();
      if (!classifier.accept(header, carrier.getHeader(header))) {
        return;
      }
    }
  }
}
