package datadog.trace.instrumentation.liberty20;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.util.Enumeration;
import javax.servlet.http.HttpServletRequest;

public class RequestExtractAdapter implements AgentPropagation.ContextVisitor<HttpServletRequest> {

  public static final RequestExtractAdapter GETTER = new RequestExtractAdapter();

  @Override
  public void forEachKey(HttpServletRequest carrier, AgentPropagation.KeyClassifier classifier) {
    Enumeration<String> headerNames = carrier.getHeaderNames();
    while (headerNames.hasMoreElements()) {
      String header = headerNames.nextElement();
      if (!classifier.accept(header, carrier.getHeader(header))) {
        break;
      }
    }
  }
}
