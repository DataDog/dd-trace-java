package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.trace.api.Functions.PATH_BASED_RESOURCE_NAME;
import static datadog.trace.api.cache.RadixTreeCache.UNSET_STATUS;
import static datadog.trace.api.http.UrlBasedResourceNameCalculator.SIMPLE_PATH_NORMALIZER;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.Pair;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.URIUtils;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.BitSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class HttpClientDecorator<REQUEST, RESPONSE> extends ClientDecorator {

  private static final Logger log = LoggerFactory.getLogger(HttpClientDecorator.class);

  private static final BitSet CLIENT_ERROR_STATUSES = Config.get().getHttpClientErrorStatuses();

  private static final UTF8BytesString DEFAULT_RESOURCE_NAME = UTF8BytesString.create("/");

  private static final DDCache<Pair<String, String>, UTF8BytesString> RESOURCE_NAMES =
      DDCaches.newFixedSizeCache(512);

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
          if (null != host) {
            span.setTag(Tags.PEER_HOSTNAME, host);
            if (Config.get().isHttpClientSplitByDomain()) {
              span.setServiceName(host);
            }
            if (url.getPort() > 0) {
              setPeerPort(span, url.getPort());
            }
          }

          if (Config.get().isHttpClientTagQueryString()) {
            span.setTag(DDTags.HTTP_QUERY, url.getQuery());
            span.setTag(DDTags.HTTP_FRAGMENT, url.getFragment());
          }
          if (shouldSetResourceName() && !span.hasResourceName()) {
            span.setResourceName(
                RESOURCE_NAMES.computeIfAbsent(
                    Pair.of(method, SIMPLE_PATH_NORMALIZER.normalize(path)),
                    PATH_BASED_RESOURCE_NAME));
          }
        } else if (shouldSetResourceName() && !span.hasResourceName()) {
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
