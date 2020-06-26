package datadog.trace.agent.test.server.http;

import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.KeyClassifier.IGNORE;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.CachingContextVisitor;
import java.util.Enumeration;
import javax.servlet.http.HttpServletRequest;

/**
 * Tracer extract adapter for {@link HttpServletRequest}.
 *
 * @author Pavol Loffay
 */
// FIXME:  This code is duplicated in several places.  Extract to a common dependency.
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
  }
}
