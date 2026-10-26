package datadog.trace.bootstrap.instrumentation.api.http;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.KnownTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.TagExtractor;

/** Extracts the Datadog URL tags from a captured HTTP server URL. */
public final class DatadogServerUrl implements TagExtractor<HttpServerUrl> {
  public static final DatadogServerUrl INSTANCE = new DatadogServerUrl(false);
  public static final DatadogServerUrl URL_ONLY = new DatadogServerUrl(true);

  private final boolean urlOnly;

  private DatadogServerUrl(boolean urlOnly) {
    this.urlOnly = urlOnly;
  }

  @Override
  public void extract(HttpServerUrl url, AgentSpan span) {
    CharSequence legacyUrl = url.datadogUrl();
    if (legacyUrl != null) {
      span.setTag(KnownTags.HTTP_URL_ID, legacyUrl);
    }
    if (!urlOnly) {
      String hostname = url.datadogHostname();
      if (hostname != null) {
        span.setTag(KnownTags.HTTP_HOSTNAME_ID, hostname);
      }
      if (url.isValid() && Config.get().isHttpServerTagQueryString()) {
        span.setTag(KnownTags.HTTP_QUERY_STRING_ID, url.query());
        span.setTag(DDTags.HTTP_FRAGMENT, url.fragment());
      }
    }
  }
}
