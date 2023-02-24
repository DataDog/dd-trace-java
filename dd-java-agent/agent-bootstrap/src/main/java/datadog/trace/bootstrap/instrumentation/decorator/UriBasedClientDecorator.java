package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.api.Config;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.net.URI;
import java.util.function.Function;
import javax.annotation.Nonnull;

public abstract class UriBasedClientDecorator extends ClientDecorator {
  private static final DDCache<String, CharSequence> CACHE = DDCaches.newFixedSizeCache(16);

  private static final Function<String, CharSequence> ADDER =
      protocol ->
          UTF8BytesString.create(SpanNaming.instance().namingSchema().client().operation(protocol));

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

  public CharSequence operationName(@Nonnull final String protocol) {
    return CACHE.computeIfAbsent(protocol, ADDER);
  }
}
