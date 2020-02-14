package datadog.trace.instrumentation.servlet3;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.util.Collections;
import java.util.List;
import javax.servlet.http.HttpServletRequest;

public class HttpServletRequestExtractAdapter
    implements AgentPropagation.Getter<HttpServletRequest> {

  public static final HttpServletRequestExtractAdapter GETTER =
      new HttpServletRequestExtractAdapter();

  @Override
  public List<String> keys(final HttpServletRequest carrier) {
    final List<String> keys = Collections.list(carrier.getHeaderNames());
    keys.addAll(Collections.list(carrier.getAttributeNames()));
    return keys;
  }

  @Override
  public String get(final HttpServletRequest carrier, final String key) {
    /*
     * Read from the attributes and override the headers.
     * This is used by HttpServletRequestSetter when a request is async-dispatched.
     */
    final Object attribute = carrier.getAttribute(key);
    if (attribute instanceof String) {
      return (String) attribute;
    }
    return carrier.getHeader(key);
  }
}
