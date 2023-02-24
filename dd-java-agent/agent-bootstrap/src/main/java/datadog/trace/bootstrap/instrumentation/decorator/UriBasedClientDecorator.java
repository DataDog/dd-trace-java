package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.net.URI;
import javax.annotation.Nonnull;

public abstract class UriBasedClientDecorator extends ClientDecorator {

  public void onURI(@Nonnull final AgentSpan span, @Nonnull final URI uri) {
    String host = uri.getHost();
    int port = uri.getPort();
    if (null != host && !host.isEmpty()) {
      span.setTag(Tags.PEER_HOSTNAME, host);
      if (Config.get().isHttpClientSplitByDomain() && host.charAt(0) >= 'A') {
        span.setServiceName(host);
      }
      if (port > 0) {
        setPeerPort(span, port);
      }
    }
  }
}
