package datadog.trace.agent.test.server.http;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.util.Enumeration;
import javax.servlet.http.HttpServletRequest;

/**
 * Tracer extract adapter for {@link HttpServletRequest}.
 *
 * @author Pavol Loffay
 */
// FIXME:  This code is duplicated in several places.  Extract to a common dependency.
public class HttpServletRequestExtractAdapter
    implements AgentPropagation.ContextVisitor<HttpServletRequest> {

  public static final HttpServletRequestExtractAdapter GETTER =
      new HttpServletRequestExtractAdapter();

  @Override
  public void forEachKey(
      final HttpServletRequest carrier, final AgentPropagation.KeyClassifier classifier) {
    Enumeration<String> headerNames = carrier.getHeaderNames();
    while (headerNames.hasMoreElements()) {
      final String header = headerNames.nextElement();
      if (!classifier.accept(header, carrier.getHeader(header))) {
        return;
      }
    }
  }
}
