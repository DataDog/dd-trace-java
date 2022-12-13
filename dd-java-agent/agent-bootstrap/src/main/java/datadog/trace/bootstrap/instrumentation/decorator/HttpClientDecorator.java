package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.trace.api.cache.RadixTreeCache.UNSET_STATUS;
import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.URIUtils;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.BitSet;
import java.util.LinkedHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class HttpClientDecorator<REQUEST, RESPONSE> extends ClientDecorator {
  public static final LinkedHashMap<String, String> CLIENT_PATHWAY_EDGE_TAGS;

  static {
    CLIENT_PATHWAY_EDGE_TAGS = new LinkedHashMap<>(2);
    // TODO: Refactor TagsProcessor to move it into a package that we can link the constants for.
    CLIENT_PATHWAY_EDGE_TAGS.put("direction", "out");
    CLIENT_PATHWAY_EDGE_TAGS.put("type", "http");
  }

  private static final Logger log = LoggerFactory.getLogger(HttpClientDecorator.class);

  private static final BitSet CLIENT_ERROR_STATUSES = Config.get().getHttpClientErrorStatuses();

  private static final UTF8BytesString DEFAULT_RESOURCE_NAME = UTF8BytesString.create("/");

  protected abstract String method(REQUEST request);

  protected abstract URI url(REQUEST request) throws URISyntaxException;

  protected abstract int status(RESPONSE response);

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.HTTP_CLIENT;
  }

  @Override
  protected String service() {
    return null;
  }

  protected boolean shouldSetResourceName() {
    return true;
  }

  public AgentSpan onRequest(final AgentSpan span, final REQUEST request) {
    if (request != null) {
      String method = method(request);
      span.setTag(Tags.HTTP_METHOD, method);

      // Copy of HttpServerDecorator url handling
      try {
        final URI url = url(request);
        if (url != null) {
          String host = url.getHost();
          String path = url.getPath();
          int port = url.getPort();
          span.setTag(Tags.HTTP_URL, URIUtils.buildURL(url.getScheme(), host, port, path));
          if (null != host && !host.isEmpty()) {
            span.setTag(Tags.PEER_HOSTNAME, host);
            if (Config.get().isHttpClientSplitByDomain() && host.charAt(0) >= 'A') {
              span.setServiceName(host);
            }
            if (port > 0) {
              setPeerPort(span, port);
            }
          }

          if (Config.get().isHttpClientTagQueryString()) {
            span.setTag(DDTags.HTTP_QUERY, url.getQuery());
            span.setTag(DDTags.HTTP_FRAGMENT, url.getFragment());
          }
          if (shouldSetResourceName()) {
            HTTP_RESOURCE_DECORATOR.withClientPath(span, method, path);
          }
        } else if (shouldSetResourceName()) {
          span.setResourceName(DEFAULT_RESOURCE_NAME);
        }
      } catch (final Exception e) {
        log.debug("Error tagging url", e);
      }
    }
    return span;
  }

  public AgentSpan onResponse(final AgentSpan span, final RESPONSE response) {
    if (response != null) {
      final int status = status(response);
      if (status > UNSET_STATUS) {
        span.setHttpStatusCode(status);
      }
      if (CLIENT_ERROR_STATUSES.get(status)) {
        span.setError(true);
      }
    }
    return span;
  }
}
