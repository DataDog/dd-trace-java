package datadog.trace.instrumentation.servlet2;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.util.Enumeration;
import javax.servlet.http.HttpServletRequest;

public class HttpServletRequestExtractAdapter
    implements AgentPropagation.ContextVisitor<HttpServletRequest> {

  public static final HttpServletRequestExtractAdapter GETTER =
      new HttpServletRequestExtractAdapter();

  @Override
  public void forEachKey(HttpServletRequest carrier, AgentPropagation.KeyClassifier classifier) {
    Enumeration<String> headerNames = carrier.getHeaderNames();
    while (headerNames.hasMoreElements()) {
      String header = headerNames.nextElement();
      if (!classifier.accept(header, carrier.getHeader(header))) {
        return;
      }
    }
  }
}
