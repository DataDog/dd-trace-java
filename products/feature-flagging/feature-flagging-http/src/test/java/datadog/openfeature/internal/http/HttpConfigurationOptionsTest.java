package datadog.openfeature.internal.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class HttpConfigurationOptionsTest {

  @Test
  void buildsImmutableOptionsAndDefaults() {
    final HttpConfigurationOptions options =
        HttpConfigurationOptions.builder()
            .endpoint(URI.create("https://example.test/config"))
            .apiKey("key")
            .managedEndpoint(true)
            .build();

    assertEquals(Duration.ofSeconds(30), options.pollInterval);
    assertEquals(Duration.ofSeconds(5), options.requestTimeout);
    assertEquals("key", options.apiKey);
    assertTrue(options.managedEndpoint);
  }

  @Test
  void validatesRequiredAndPositiveOptions() {
    assertThrows(NullPointerException.class, () -> HttpConfigurationOptions.builder().build());
    assertThrows(
        NullPointerException.class,
        () ->
            HttpConfigurationOptions.builder()
                .endpoint(URI.create("https://example.test"))
                .pollInterval(null)
                .build());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HttpConfigurationOptions.builder()
                .endpoint(URI.create("https://example.test"))
                .pollInterval(Duration.ZERO)
                .build());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HttpConfigurationOptions.builder()
                .endpoint(URI.create("https://example.test"))
                .requestTimeout(Duration.ofSeconds(-1))
                .build());
  }
}
