package com.datadog.openfeature.internal.delivery;

import java.net.URI;
import java.net.URISyntaxException;

/** Builds the host of a Datadog endpoint from the configured site. */
final class SiteHost {
  private SiteHost() {}

  /**
   * Builds the host of a Datadog endpoint.
   *
   * @param prefix the endpoint host prefix.
   * @param site the configured Datadog site.
   * @return the endpoint host.
   * @throws IllegalArgumentException if the site does not make a plain host name.
   */
  static String of(final String prefix, final String site) {
    final String host = prefix + "." + site;
    try {
      final URI uri = new URI("https", null, host, -1, "/", null, null);
      if (host.equalsIgnoreCase(uri.getHost())) {
        return host;
      }
    } catch (final URISyntaxException ignored) {
      // Reported below
    }
    throw new IllegalArgumentException("Invalid Datadog site: " + site);
  }
}
