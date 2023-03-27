package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.function.Function;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UrlConnectionDecorator extends UriBasedClientDecorator {
  private static final DDCache<String, CharSequence> CACHE = DDCaches.newFixedSizeCache(16);

  private static final Function<String, CharSequence> ADDER =
      protocol ->
          UTF8BytesString.create(
              SpanNaming.instance().namingSchema().client().operationForProtocol(protocol));

  private static final Logger LOGGER = LoggerFactory.getLogger(UrlConnectionDecorator.class);

  public static final CharSequence COMPONENT = UTF8BytesString.create("UrlConnection");
  public static final UrlConnectionDecorator DECORATE = new UrlConnectionDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"urlconnection", "httpurlconnection"};
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.HTTP_CLIENT;
  }

  @Override
  protected CharSequence component() {
    return COMPONENT;
  }

  @Override
  protected String service() {
    return null;
  }

  @Override
  public void onURI(@Nonnull AgentSpan span, @Nonnull URI uri) {
    super.onURI(span, uri);
    span.setTag(Tags.HTTP_URL, uri.toString());
  }

  public void onURL(@Nonnull final AgentSpan span, @Nonnull final URL url) {
    try {
      onURI(span, url.toURI());
    } catch (URISyntaxException e) {
      LOGGER.debug("Error tagging url", e);
    }
  }

  public CharSequence operationName(@Nonnull final String protocol) {
    return CACHE.computeIfAbsent(protocol, ADDER);
  }
}
