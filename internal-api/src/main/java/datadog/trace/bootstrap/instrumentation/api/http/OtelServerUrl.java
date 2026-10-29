package datadog.trace.bootstrap.instrumentation.api.http;

import datadog.trace.api.Config;
import datadog.trace.api.KnownTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.TagExtractor;

/** Extracts the OpenTelemetry URL tags from a captured HTTP server URL. */
public final class OtelServerUrl implements TagExtractor<HttpServerUrl> {
  public static final OtelServerUrl INSTANCE = new OtelServerUrl();

  private OtelServerUrl() {}

  @Override
  public void extract(HttpServerUrl url, AgentSpan span) {
    HttpServerUrl.Origin origin = url.origin();
    if (url.isValid() && origin.scheme != null) {
      span.setTag(KnownTags.URL_SCHEME_ID, origin.scheme);
    }
    String path = url.path();
    if (path != null) {
      span.setTag(KnownTags.URL_PATH_ID, path);
    }
    if (origin.address != null && !origin.address.isEmpty()) {
      span.setTag(KnownTags.Inbound.SERVER_ADDRESS_ID, origin.address);
    }
    if (origin.port > 0) {
      span.setTag(KnownTags.Inbound.SERVER_PORT_ID, origin.port);
    }
    if (Config.get().isHttpServerTagQueryString()) {
      String query = url.query();
      if (query != null && !query.isEmpty()) {
        span.setTag(KnownTags.URL_QUERY_ID, query);
      }
    }
  }
}
