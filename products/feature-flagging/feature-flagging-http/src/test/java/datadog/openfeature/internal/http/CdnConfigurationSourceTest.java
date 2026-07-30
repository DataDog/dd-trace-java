package datadog.openfeature.internal.http;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.openfeature.internal.core.ConfigurationStore;
import datadog.openfeature.internal.core.SourceStatus;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class CdnConfigurationSourceTest {

  @Test
  void keepsEtagAndLastKnownGoodAfterMalformedPayload() {
    final QueueTransport transport = new QueueTransport();
    transport.responses.add(response(200, "\"one\"", UFC));
    transport.responses.add(response(200, "\"two\"", "{"));
    transport.responses.add(response(304, null, null));
    transport.responses.add(response(200, "\"three\"", UFC.replace("hello", "recovered")));
    final ConfigurationStore store = new ConfigurationStore();
    final CdnConfigurationSource source = source(store, transport, millis -> {});

    assertTrue(source.pollOnce());
    final Object accepted = store.current();
    assertFalse(source.pollOnce());
    assertEquals(accepted, store.current());
    assertTrue(source.pollOnce());
    assertEquals("\"one\"", transport.requestHeaders.get(2).get("If-None-Match"));
    assertTrue(source.pollOnce());
    assertEquals("\"one\"", transport.requestHeaders.get(3).get("If-None-Match"));
    assertEquals("recovered", store.current().flags.get("message").variations.get("on").value);
  }

  @Test
  void retriesTimeoutAndServerError() {
    final QueueTransport transport = new QueueTransport();
    transport.failures.add(new IOException("timeout"));
    transport.responses.add(response(503, null, null));
    transport.responses.add(response(200, null, UFC));
    final List<Long> delays = new ArrayList<>();
    final CdnConfigurationSource source = source(new ConfigurationStore(), transport, delays::add);

    assertTrue(source.pollOnce());
    assertEquals(2, delays.size());
    assertEquals(3, transport.requestHeaders.size());
  }

  @Test
  void preventsOverlappingPollsAndCancelsOnClose() throws Exception {
    final BlockingTransport transport = new BlockingTransport();
    final ConfigurationStore store = new ConfigurationStore();
    final CdnConfigurationSource source = source(store, transport, millis -> {});
    final Thread first = new Thread(source::pollOnce);
    first.start();
    assertTrue(transport.entered.await(1, TimeUnit.SECONDS));

    assertFalse(source.pollOnce());
    source.close();
    transport.release.countDown();
    first.join(1_000);

    assertTrue(transport.cancelled.get());
    assertEquals(SourceStatus.CLOSED, source.status());
    assertFalse(store.hasConfiguration());
  }

  @Test
  void startPollsImmediatelyAndCloseCancelsSchedule() {
    final QueueTransport transport = new QueueTransport();
    transport.responses.add(response(200, null, UFC));
    final CdnConfigurationSource source = source(new ConfigurationStore(), transport, millis -> {});

    source.start();
    source.start();
    assertEquals(SourceStatus.READY, source.status());
    assertEquals(1, transport.requestHeaders.size());

    source.close();
    source.close();
    assertTrue(transport.cancelled.get());
    assertFalse(source.pollOnce());
    source.start();
    assertEquals(1, transport.requestHeaders.size());
  }

  @Test
  void doesNotRetryPermanentFailures() {
    final QueueTransport transport = new QueueTransport();
    transport.responses.add(response(401, null, null));
    final CdnConfigurationSource source = source(new ConfigurationStore(), transport, millis -> {});

    assertFalse(source.pollOnce());
    assertEquals(1, transport.requestHeaders.size());
  }

  @Test
  void calculatesBoundedRetryDelays() {
    assertEquals(5_000, CdnConfigurationSource.retryDelayMillis(30_000, 1, 1));
    assertEquals(10_000, CdnConfigurationSource.retryDelayMillis(600_000, 1, 1));
    assertEquals(5_000, CdnConfigurationSource.retryDelayMillis(3_000, 2, 1));
    assertEquals(30_000, CdnConfigurationSource.retryDelayMillis(600_000, 2, 1));
  }

  private static CdnConfigurationSource source(
      final ConfigurationStore store,
      final CdnConfigurationSource.Transport transport,
      final CdnConfigurationSource.Sleeper sleeper) {
    final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    final HttpConfigurationOptions options =
        HttpConfigurationOptions.builder()
            .endpoint(URI.create("https://example.test/config"))
            .pollInterval(Duration.ofHours(1))
            .requestTimeout(Duration.ofSeconds(1))
            .build();
    return new CdnConfigurationSource(options, store, transport, executor, sleeper, () -> 1);
  }

  private static CdnConfigurationSource.TransportResponse response(
      final int status, final String etag, final String body) {
    return new CdnConfigurationSource.TransportResponse(
        status, etag, body == null ? null : body.getBytes(UTF_8));
  }

  private static class QueueTransport implements CdnConfigurationSource.Transport {
    final Queue<CdnConfigurationSource.TransportResponse> responses = new ArrayDeque<>();
    final Queue<IOException> failures = new ArrayDeque<>();
    final List<Map<String, String>> requestHeaders = new ArrayList<>();
    final AtomicBoolean cancelled = new AtomicBoolean();

    @Override
    public CdnConfigurationSource.TransportResponse fetch(
        final HttpConfigurationOptions options, final Map<String, String> headers)
        throws IOException {
      requestHeaders.add(headers);
      final IOException failure = failures.poll();
      if (failure != null) {
        throw failure;
      }
      return responses.remove();
    }

    @Override
    public void cancel() {
      cancelled.set(true);
    }
  }

  private static final class BlockingTransport extends QueueTransport {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);

    @Override
    public CdnConfigurationSource.TransportResponse fetch(
        final HttpConfigurationOptions options, final Map<String, String> headers)
        throws IOException {
      requestHeaders.add(headers);
      entered.countDown();
      try {
        release.await();
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException(e);
      }
      return response(200, null, UFC);
    }
  }

  private static final String UFC =
      "{\"format\":\"SERVER\",\"environment\":{\"name\":\"test\"},\"flags\":{"
          + "\"message\":{\"key\":\"message\",\"enabled\":true,\"variationType\":\"STRING\","
          + "\"variations\":{\"on\":{\"key\":\"on\",\"value\":\"hello\"}},"
          + "\"allocations\":[{\"key\":\"a\",\"rules\":[],\"splits\":["
          + "{\"variationKey\":\"on\",\"shards\":[]}]}]}}}";
}
