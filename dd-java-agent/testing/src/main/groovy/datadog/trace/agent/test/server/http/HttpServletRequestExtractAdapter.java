package datadog.trace.agent.test.server.http;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import io.opentracing.propagation.TextMap;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;

/**
 * Tracer extract adapter for {@link HttpServletRequest}.
 *
 * <p>FIXME: we have 4 copies of this class now - we should really make this a utility or something
 */
public class HttpServletRequestExtractAdapter implements TextMap {

  private final Multimap<String, String> headers;

  public HttpServletRequestExtractAdapter(final HttpServletRequest httpServletRequest) {
    headers = servletHeadersToMultimap(httpServletRequest);
  }

  @Override
  public Iterator<Map.Entry<String, String>> iterator() {
    return headers.entries().iterator();
  }

  @Override
  public void put(final String key, final String value) {
    throw new UnsupportedOperationException("This class should be used only with Tracer.inject()!");
  }

  protected ImmutableMultimap<String, String> servletHeadersToMultimap(
      final HttpServletRequest httpServletRequest) {
    final ImmutableMultimap.Builder<String, String> builder = new ImmutableMultimap.Builder<>();

    final Enumeration<String> headerNamesIt = httpServletRequest.getHeaderNames();
    while (headerNamesIt.hasMoreElements()) {
      final String headerName = headerNamesIt.nextElement();

      final Enumeration<String> valuesIt = httpServletRequest.getHeaders(headerName);
      while (valuesIt.hasMoreElements()) {
        builder.put(headerName, valuesIt.nextElement());
      }
    }

    return builder.build();
  }
}
