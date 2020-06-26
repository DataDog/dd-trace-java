package datadog.trace.instrumentation.servlet3;

import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.KeyClassifier.IGNORE;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.CachingContextVisitor;
import java.util.Enumeration;
import javax.servlet.http.HttpServletRequest;

public class HttpServletRequestExtractAdapter extends CachingContextVisitor<HttpServletRequest> {

  public static final HttpServletRequestExtractAdapter GETTER =
      new HttpServletRequestExtractAdapter();

  @Override
  public void forEachKey(
      HttpServletRequest carrier,
      AgentPropagation.KeyClassifier classifier,
      AgentPropagation.KeyValueConsumer consumer) {
    Enumeration<String> headerNames = carrier.getHeaderNames();
    while (headerNames.hasMoreElements()) {
      String header = headerNames.nextElement();
      String lowerCaseKey = toLowerCase(header);
      int classification = classifier.classify(lowerCaseKey);
      if (classification != IGNORE) {
        if (!consumer.accept(classification, lowerCaseKey, carrier.getHeader(header))) {
          return;
        }
      }
    }
    // TODO collapse these into one method with a lambda when JDK7 is dropped
    Enumeration<String> attributeNames = carrier.getAttributeNames();
    while (attributeNames.hasMoreElements()) {
      String attribute = attributeNames.nextElement();
      String lowerCaseKey = toLowerCase(attribute);
      int classification = classifier.classify(lowerCaseKey);
      if (classification != IGNORE) {
        Object value = carrier.getAttribute(attribute);
        if (value instanceof String) {
          if (!consumer.accept(classification, lowerCaseKey, (String) value)) {
            return;
          }
        }
      }
    }
  }
}
