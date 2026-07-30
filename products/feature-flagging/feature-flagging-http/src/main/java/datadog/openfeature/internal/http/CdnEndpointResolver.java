package datadog.openfeature.internal.http;

import java.net.URI;
import java.net.URISyntaxException;

/** Resolves the managed CDN endpoint or a test-controlled endpoint. */
public final class CdnEndpointResolver {

  public static final String UFC_PATH = "/api/v2/feature-flagging/config/rules-based/server";

  private CdnEndpointResolver() {}

  public static URI resolve(final String configuredBaseUrl, final String site, final String env) {
    if (configuredBaseUrl != null && !configuredBaseUrl.trim().isEmpty()) {
      final URI configured = URI.create(configuredBaseUrl.trim());
      if (!configured.isAbsolute() || configured.getHost() == null) {
        throw new IllegalArgumentException(
            "Invalid Feature Flagging HTTP configuration source URL: " + configuredBaseUrl);
      }
      final String path = configured.getPath();
      if (path == null || path.isEmpty() || "/".equals(path)) {
        final String query = configured.getRawQuery();
        return configured.resolve(URI.create(UFC_PATH + (query == null ? "" : "?" + query)));
      }
      return configured;
    }

    try {
      return new URI(
          "https",
          null,
          "ufc-server.ff-cdn." + (site == null || site.isEmpty() ? "datadoghq.com" : site),
          -1,
          UFC_PATH,
          env == null || env.isEmpty() ? null : "dd_env=" + env,
          null);
    } catch (final URISyntaxException e) {
      throw new IllegalArgumentException("Invalid Feature Flagging CDN endpoint", e);
    }
  }
}
