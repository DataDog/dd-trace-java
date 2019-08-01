package datadog.trace.instrumentation.servlet3;

import datadog.trace.instrumentation.api.Propagation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.servlet.http.HttpServletRequest;

public class HttpServletRequestGetter implements Propagation.Getter<HttpServletRequest> {
  public static HttpServletRequestGetter GETTER = new HttpServletRequestGetter();

  @Override
  public List<String> keys(final HttpServletRequest carrier) {
    final ArrayList<String> keys = Collections.list(carrier.getHeaderNames());
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
