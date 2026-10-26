package datadog.trace.bootstrap.instrumentation.api.http;

import datadog.trace.api.Config;
import datadog.trace.api.KnownTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.OtelHttpSemantics;
import datadog.trace.bootstrap.instrumentation.api.TagExtractor;
import datadog.trace.bootstrap.instrumentation.api.URIUtils;
import java.net.URI;

/** Extracts the OpenTelemetry URL tags from an HTTP client URI. */
public final class OtelClientUrl implements TagExtractor<URI> {
  public static final OtelClientUrl INSTANCE = new OtelClientUrl();

  private OtelClientUrl() {}

  @Override
  public void extract(URI uri, AgentSpan span) {
    // The Object overload avoids calling length(), which would render the lazy URL immediately.
    span.setTag(KnownTags.HTTP_URL_ID, (Object) new ClientUrl(uri));
    if (Config.get().isHttpClientTagQueryString()) {
      // The obfuscator matches decoded parameter names, including names such as t%6fken.
      String query = URIUtils.preserveQueryDelimiters(uri.getRawQuery(), uri.getQuery());
      if (query != null) {
        span.setTag(KnownTags.URL_QUERY_ID, query);
      }
    }
  }

  private static final class ClientUrl extends URIUtils.LazyUrl {
    private volatile URI uri;
    private String path;

    private ClientUrl(URI uri) {
      super(null);
      this.uri = uri;
    }

    @Override
    public String path() {
      URI source = uri;
      return source != null ? source.getPath() : path;
    }

    @Override
    public String get() {
      URI source = uri;
      if (source != null) {
        path = source.getPath();
        lazy = OtelHttpSemantics.withoutQuery(OtelHttpSemantics.redactedUrl(source));
        // Publish both cached values before releasing the URI. Concurrent renders are harmless.
        uri = null;
      }
      return lazy;
    }
  }
}
