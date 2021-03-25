package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.trace.api.Functions.PATH_BASED_RESOURCE_NAME;
import static datadog.trace.api.cache.RadixTreeCache.HTTP_STATUSES;
import static datadog.trace.api.cache.RadixTreeCache.UNSET_STATUS;
import static datadog.trace.api.normalize.PathNormalizer.normalize;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.Pair;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.BitSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class HttpServerDecorator<REQUEST, CONNECTION, RESPONSE> extends ServerDecorator {

  private static final Logger log = LoggerFactory.getLogger(HttpServerDecorator.class);

  public static final String DD_SPAN_ATTRIBUTE = "datadog.span";
  public static final String DD_DISPATCH_SPAN_ATTRIBUTE = "datadog.span.dispatch";
  public static final String DD_RESPONSE_ATTRIBUTE = "datadog.response";
  private static final UTF8BytesString DEFAULT_RESOURCE_NAME = UTF8BytesString.create("/");
  protected static final UTF8BytesString NOT_FOUND_RESOURCE_NAME = UTF8BytesString.create("404");
  private static final boolean SET_404_RESOURCE_NAME =
      Config.get().isRuleEnabled("Status404Rule")
          && Config.get().isRuleEnabled("Status404Decorator");

  private static final BitSet SERVER_ERROR_STATUSES = Config.get().getHttpServerErrorStatuses();

  private static final DDCache<Pair<String, String>, UTF8BytesString> RESOURCE_NAMES =
      DDCaches.newFixedSizeCache(512);

  // Assigned here to avoid repeat boxing and cache lookup.
  public static final Integer _500 = HTTP_STATUSES.get(500);

  protected abstract String method(REQUEST request);

  protected abstract URIDataAdapter url(REQUEST request);

  protected abstract String peerHostIP(CONNECTION connection);

  protected abstract int peerPort(CONNECTION connection);

  protected abstract int status(RESPONSE response);

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.HTTP_SERVER;
  }

  @Override
  protected boolean traceAnalyticsDefault() {
    return Config.get().isTraceAnalyticsEnabled();
  }

  public AgentSpan onRequest(
      final AgentSpan span,
      final CONNECTION connection,
      final REQUEST request,
      final AgentSpan.Context.Extracted context) {
    String forwarded = null;
    String forwardedPort = null;
    if (context != null) {
      forwarded = context.getForwardedFor();
      forwardedPort = context.getForwardedPort();
    }

    if (request != null) {
      String method = method(request);
      span.setTag(Tags.HTTP_METHOD, method);

      // Copy of HttpClientDecorator url handling
      try {
        final URIDataAdapter url = url(request);
        if (url != null) {
          span.setTag(Tags.HTTP_URL, buildURL(url));

          if (Config.get().isHttpServerTagQueryString()) {
            span.setTag(DDTags.HTTP_QUERY, url.query());
            span.setTag(DDTags.HTTP_FRAGMENT, url.fragment());
          }
          if (!span.hasResourceName()) {
            span.setResourceName(
                RESOURCE_NAMES.computeIfAbsent(
                    Pair.of(method, normalize(url.path())), PATH_BASED_RESOURCE_NAME));
          }
        } else if (!span.hasResourceName()) {
          span.setResourceName(DEFAULT_RESOURCE_NAME);
        }
      } catch (final Exception e) {
        log.debug("Error tagging url", e);
      }
      // TODO set resource name from URL.
    }

    final String ip = forwarded != null || connection == null ? forwarded : peerHostIP(connection);
    if (ip != null) {
      if (ip.indexOf(':') > 0) {
        span.setTag(Tags.PEER_HOST_IPV6, ip);
      } else {
        span.setTag(Tags.PEER_HOST_IPV4, ip);
      }
    }

    if (forwardedPort != null) {
      setPeerPort(span, forwardedPort);
    } else if (connection != null) {
      setPeerPort(span, peerPort(connection));
    }
    return span;
  }

  public AgentSpan onResponse(final AgentSpan span, final RESPONSE response) {
    if (response != null) {
      final int status = status(response);
      if (status > UNSET_STATUS) {
        span.setTag(Tags.HTTP_STATUS, HTTP_STATUSES.get(status));
      }
      if (SERVER_ERROR_STATUSES.get(status)) {
        span.setError(true);
      }
      // FIXME - users usually disable this and if there was a path available
      //  this will have been set in onRequest
      if (SET_404_RESOURCE_NAME && status == 404 && !span.hasResourceName()) {
        span.setResourceName(NOT_FOUND_RESOURCE_NAME);
      }
    }
    return span;
  }

  private static String buildURL(URIDataAdapter uri) {
    String scheme = uri.scheme();
    String host = uri.host();
    String path = uri.path();
    int port = uri.port();
    int length = 0;
    length += null == scheme ? 0 : scheme.length() + 3;
    if (null != host) {
      length += host.length();
      if (port > 0 && port != 80 && port != 443) {
        length += 6;
      }
    }
    if (null == path || path.isEmpty()) {
      ++length;
    } else {
      if (path.charAt(0) != '/') {
        ++length;
      }
      length += path.length();
    }
    final StringBuilder urlNoParams = new StringBuilder(length);
    if (scheme != null) {
      urlNoParams.append(scheme);
      urlNoParams.append("://");
    }

    if (host != null) {
      urlNoParams.append(host);
      if (port > 0 && port != 80 && port != 443) {
        urlNoParams.append(':');
        urlNoParams.append(port);
      }
    }

    if (null == path || path.isEmpty()) {
      urlNoParams.append('/');
    } else {
      if (path.charAt(0) != '/') {
        urlNoParams.append('/');
      }
      urlNoParams.append(path);
    }
    return urlNoParams.toString();
  }

  //  @Override
  //  public Span onError(final Span span, final Throwable throwable) {
  //    assert span != null;
  //    // FIXME
  //    final Object status = span.getTag("http.status");
  //    if (status == null || status.equals(200)) {
  //      // Ensure status set correctly
  //      span.setTag("http.status", 500);
  //    }
  //    return super.onError(span, throwable);
  //  }
}
