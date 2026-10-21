package datadog.trace.bootstrap.instrumentation.api.http;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.KnownTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.TagExtractor;
import datadog.trace.bootstrap.instrumentation.api.URIUtils;
import java.net.URI;

/** Extracts the Datadog URL tags from an HTTP client URI. */
public final class DatadogClientUrl implements TagExtractor<URI> {
  public static final DatadogClientUrl INSTANCE = new DatadogClientUrl(false);
  public static final DatadogClientUrl FULL_URL = new DatadogClientUrl(true);

  private final boolean fullUrl;

  private DatadogClientUrl(boolean fullUrl) {
    this.fullUrl = fullUrl;
  }

  @Override
  public void extract(URI uri, AgentSpan span) {
    if (fullUrl) {
      // URLConnection historically records the original URL, including its query and fragment.
      span.setTag(KnownTags.HTTP_URL_ID, uri.toString());
    } else {
      span.setTag(
          KnownTags.HTTP_URL_ID,
          URIUtils.lazyValidURL(uri.getScheme(), uri.getHost(), uri.getPort(), uri.getPath()));
      if (Config.get().isHttpClientTagQueryString()) {
        span.setTag(KnownTags.HTTP_QUERY_STRING_ID, uri.getQuery());
        span.setTag(DDTags.HTTP_FRAGMENT, uri.getFragment());
      }
    }
  }
}
