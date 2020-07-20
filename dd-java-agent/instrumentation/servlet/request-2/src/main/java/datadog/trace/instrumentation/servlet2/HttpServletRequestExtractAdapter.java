package datadog.trace.instrumentation.servlet2;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.util.Enumeration;
import javax.servlet.http.HttpServletRequest;

public class HttpServletRequestExtractAdapter
    implements AgentPropagation.ContextVisitor<HttpServletRequest> {

  public static final HttpServletRequestExtractAdapter GETTER =
      new HttpServletRequestExtractAdapter();

  @Override
  @SuppressWarnings("unchecked")
  public void forEachKey(HttpServletRequest carrier, AgentPropagation.KeyClassifier classifier) {
    Enumeration<String> headerNames = carrier.getHeaderNames();
    while (headerNames.hasMoreElements()) {
      String header = headerNames.nextElement();
      if (!classifier.accept(header, carrier.getHeader(header))) {
        break;
      }
    }
    /*
     * Read from the attributes and override the headers.
     * This is used by ServletRequestSetter when a request is async-dispatched.
     */
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
