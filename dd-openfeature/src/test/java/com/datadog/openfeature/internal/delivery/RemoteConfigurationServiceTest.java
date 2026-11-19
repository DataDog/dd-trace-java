package com.datadog.openfeature.internal.delivery;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.openfeature.internal.bridge.v1.ConfigurationSource;
import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class RemoteConfigurationServiceTest {
  @Test
  void forwardsParsedConfigurationsAndRemovals() {
    final CapturingSource source = new CapturingSource();
    final List<ServerConfiguration> received = new ArrayList<>();
    final RemoteConfigurationService service = new RemoteConfigurationService(source);
    service.start(received::add);

    source.listener.accept("{\"flags\":{}}".getBytes(UTF_8));
    source.listener.accept(null);

    assertEquals(2, received.size());
    assertNotNull(received.get(0));
    assertNull(received.get(1));
  }

  @Test
  void rejectsMalformedDocuments() {
    final CapturingSource source = new CapturingSource();
    final List<ServerConfiguration> received = new ArrayList<>();
    final RemoteConfigurationService service = new RemoteConfigurationService(source);
    service.start(received::add);

    final IllegalArgumentException rejection =
        assertThrows(
            IllegalArgumentException.class,
            () -> source.listener.accept("{\"flags\":[]}".getBytes(UTF_8)));

    assertTrue(rejection.getCause() instanceof IOException);
    assertTrue(rejection.getMessage().startsWith("Malformed flag configuration: "));
    assertEquals(0, received.size());
  }

  @Test
  void closeUnsubscribesOnce() {
    final CapturingSource source = new CapturingSource();
    final RemoteConfigurationService service = new RemoteConfigurationService(source);
    service.start(configuration -> {});
    service.start(configuration -> {});

    service.close();
    service.close();

    assertEquals(1, source.subscriptions);
    assertEquals(1, source.closes);
  }

  private static final class CapturingSource implements ConfigurationSource {
    private Consumer<byte[]> listener;
    private int subscriptions;
    private int closes;

    @Override
    public AutoCloseable subscribe(final Consumer<byte[]> listener) {
      this.listener = listener;
      this.subscriptions++;
      return () -> this.closes++;
    }
  }
}
