package datadog.trace.instrumentation.jetty8;

import datadog.trace.instrumentation.api.Propagation;
import java.util.Collections;
import java.util.List;
import javax.servlet.http.HttpServletRequest;

public class HttpServletRequestGetter implements Propagation.Getter<HttpServletRequest> {
  public static HttpServletRequestGetter GETTER = new HttpServletRequestGetter();

  @Override
  public List<String> keys(final HttpServletRequest carrier) {
    return Collections.list(carrier.getHeaderNames());
  }

  @Override
  public String get(final HttpServletRequest carrier, final String key) {
    return carrier.getHeader(key);
  }
}
