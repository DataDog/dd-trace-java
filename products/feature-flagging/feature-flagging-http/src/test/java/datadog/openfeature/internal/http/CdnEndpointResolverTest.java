package datadog.openfeature.internal.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import org.junit.jupiter.api.Test;

class CdnEndpointResolverTest {

  @Test
  void resolvesManagedEndpointWithEnvironment() {
    assertEquals(
        URI.create(
            "https://ufc-server.ff-cdn.datadoghq.eu/api/v2/feature-flagging/config/rules-based/server?dd_env=production"),
        CdnEndpointResolver.resolve(null, "datadoghq.eu", "production"));
    assertEquals(
        URI.create(
            "https://ufc-server.ff-cdn.datadoghq.com/api/v2/feature-flagging/config/rules-based/server"),
        CdnEndpointResolver.resolve(null, null, null));
  }

  @Test
  void resolvesCustomBaseUrlAndKeepsExplicitPath() {
    assertEquals(
        URI.create("http://localhost:8080/api/v2/feature-flagging/config/rules-based/server"),
        CdnEndpointResolver.resolve("http://localhost:8080/", "ignored", "ignored"));
    assertEquals(
        URI.create(
            "https://example.test/api/v2/feature-flagging/config/rules-based/server?token=a%2Fb"),
        CdnEndpointResolver.resolve("https://example.test/?token=a%2Fb", "ignored", "ignored"));
    assertEquals(
        URI.create("https://example.test/custom?query=true"),
        CdnEndpointResolver.resolve(
            " https://example.test/custom?query=true ", "ignored", "ignored"));
  }

  @Test
  void rejectsRelativeCustomUrlAndInvalidManagedSite() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CdnEndpointResolver.resolve("/relative", "datadoghq.com", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> CdnEndpointResolver.resolve(null, "invalid site", null));
  }
}
