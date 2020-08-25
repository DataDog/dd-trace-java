package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.api.Config;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class HttpServerDecorator<REQUEST, CONNECTION, RESPONSE> extends ServerDecorator {
  public static final String DD_SPAN_ATTRIBUTE = "datadog.span";
  public static final String DD_RESPONSE_ATTRIBUTE = "datadog.response";

  // Source: https://www.regextester.com/22
  private static final Pattern VALID_IPV4_ADDRESS =
      Pattern.compile(
          "^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$");

  protected abstract String method(REQUEST request);

  protected abstract URIDataAdapter url(REQUEST request);

  protected abstract String peerHostIP(CONNECTION connection);

  protected abstract Integer peerPort(CONNECTION connection);

  protected abstract Integer status(RESPONSE response);

  @Override
  protected String spanType() {
    return DDSpanTypes.HTTP_SERVER;
  }

  @Override
  protected boolean traceAnalyticsDefault() {
    return Config.get().isTraceAnalyticsEnabled();
  }

  public AgentSpan onRequest(final AgentSpan span, final REQUEST request) {
    assert span != null;
    if (request != null) {
      span.setTag(Tags.HTTP_METHOD, method(request));

      // Copy of HttpClientDecorator url handling
      try {
        final URIDataAdapter url = url(request);
        if (url != null) {
          final StringBuilder urlNoParams = new StringBuilder();
          String scheme = url.scheme();
          if (scheme != null) {
            urlNoParams.append(scheme);
            urlNoParams.append("://");
          }
          String host = url.host();
          if (host != null) {
            urlNoParams.append(host);
            int port = url.port();
            if (port > 0 && port != 80 && port != 443) {
              urlNoParams.append(":");
              urlNoParams.append(port);
            }
          }
          final String path = url.path();
          if (path.isEmpty()) {
            urlNoParams.append("/");
          } else {
            if (!path.startsWith("/")) {
              urlNoParams.append("/");
            }
            urlNoParams.append(path);
          }

          span.setTag(Tags.HTTP_URL, urlNoParams.toString());

          if (Config.get().isHttpServerTagQueryString()) {
            span.setTag(DDTags.HTTP_QUERY, url.query());
            span.setTag(DDTags.HTTP_FRAGMENT, url.fragment());
          }
        }
      } catch (final Exception e) {
        log.debug("Error tagging url", e);
      }
      // TODO set resource name from URL.
    }
    return span;
  }

  public AgentSpan onConnection(final AgentSpan span, final CONNECTION connection) {
    assert span != null;
    if (connection != null) {
      final String ip = peerHostIP(connection);
      if (ip != null) {
        if (VALID_IPV4_ADDRESS.matcher(ip).matches()) {
          span.setTag(Tags.PEER_HOST_IPV4, ip);
        } else if (ip.contains(":")) {
          span.setTag(Tags.PEER_HOST_IPV6, ip);
        }
      }
      final Integer port = peerPort(connection);
      // Negative or Zero ports might represent an unset/null value for an int type.  Skip setting.
      if (port != null && port > 0) {
        span.setTag(Tags.PEER_PORT, port);
      }
    }
    return span;
  }

  public AgentSpan onResponse(final AgentSpan span, final RESPONSE response) {
    assert span != null;
    if (response != null) {
      final Integer status = status(response);
      if (status != null) {
        span.setTag(Tags.HTTP_STATUS, status);
      }
    }
    return span;
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
