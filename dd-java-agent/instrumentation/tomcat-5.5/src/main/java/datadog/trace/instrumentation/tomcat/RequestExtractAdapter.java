package datadog.trace.instrumentation.tomcat;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.util.Enumeration;
import org.apache.coyote.Request;

public class RequestExtractAdapter implements AgentPropagation.ContextVisitor<Request> {

  public static final RequestExtractAdapter GETTER = new RequestExtractAdapter();

  @Override
  public void forEachKey(Request carrier, AgentPropagation.KeyClassifier classifier) {
    Enumeration<String> headerNames = carrier.getMimeHeaders().names();
    while (headerNames.hasMoreElements()) {
      String header = headerNames.nextElement();
      if (!classifier.accept(header, carrier.getHeader(header))) {
        return;
      }
    }
  }
}
