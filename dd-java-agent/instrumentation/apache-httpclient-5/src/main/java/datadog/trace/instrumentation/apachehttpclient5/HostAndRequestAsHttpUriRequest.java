package datadog.trace.instrumentation.apachehttpclient5;

import datadog.trace.api.iast.util.PropagationUtils;
import java.net.URI;
import java.net.URISyntaxException;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.message.BasicClassicHttpRequest;

/** Wraps HttpHost and HttpRequest into a HttpUriRequest for decorators and injectors */
public class HostAndRequestAsHttpUriRequest extends BasicClassicHttpRequest {

  private final HttpRequest actualRequest;

  public HostAndRequestAsHttpUriRequest(final HttpHost httpHost, final HttpRequest httpRequest) {
    super(httpRequest.getMethod(), httpHost, httpRequest.getPath());
    actualRequest = httpRequest;
    // Propagate in case the host or request is tainted
    PropagationUtils.taintObjectIfTainted(this, httpHost);
    PropagationUtils.taintObjectIfTainted(this, httpRequest);
  }

  @Override
  public URI getUri() throws URISyntaxException {
    URI uri = super.getUri();
    if (uri != null && uri.getHost() != null) {
      return uri;
    }
    return actualRequest.getUri();
  }

  @Override
  public void setHeader(String name, Object value) {
    actualRequest.setHeader(name, value);
  }

  @Override
  public Header getFirstHeader(String name) {
    return actualRequest.getFirstHeader(name);
  }
}
