package datadog.trace.instrumentation.servlet3;

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
    // TODO collapse these into one method with a lambda when JDK7 is dropped
    Enumeration<String> attributeNames = carrier.getAttributeNames();
    while (attributeNames.hasMoreElements()) {
      String name = attributeNames.nextElement();
      Object attribute = carrier.getAttribute(name);
      if (attribute instanceof String && !classifier.accept(name, (String) attribute)) {
        return;
      }
    }
  }
}
