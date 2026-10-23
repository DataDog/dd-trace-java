package com.datadog.featureflag;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import datadog.remoteconfig.Capabilities;
import datadog.remoteconfig.ConfigurationDeserializer;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.PollingRateHinter;
import datadog.remoteconfig.Product;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RemoteConfigServiceImplTest {
  private static final byte[] DOCUMENT = "{\"flags\":{}}".getBytes(UTF_8);

  @Test
  @SuppressWarnings("unchecked")
  void subscriptionRegistersTheProductWithARawDeserializer() throws Exception {
    final ConfigurationPoller poller = mock(ConfigurationPoller.class);
    final RemoteConfigServiceImpl service = new RemoteConfigServiceImpl(poller);

    service.subscribe(content -> {});
    service.subscribe(content -> {});

    verify(poller).addCapabilities(Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES);
    final ArgumentCaptor<ConfigurationDeserializer<byte[]>> deserializer =
        ArgumentCaptor.forClass(ConfigurationDeserializer.class);
    verify(poller).addListener(eq(Product.FFE_FLAGS), deserializer.capture(), eq(service));
    verify(poller).start();
    assertArrayEquals(DOCUMENT, deserializer.getValue().deserialize(DOCUMENT));
  }

  @Test
  void forwardsDocumentsAndRemovalsToAllSubscribers() {
    final RemoteConfigServiceImpl service =
        new RemoteConfigServiceImpl(mock(ConfigurationPoller.class));
    final List<byte[]> first = new ArrayList<>();
    final List<byte[]> second = new ArrayList<>();
    service.subscribe(first::add);
    service.subscribe(second::add);

    service.accept("key", DOCUMENT, mock(PollingRateHinter.class));
    service.accept("key", null, mock(PollingRateHinter.class));

    assertEquals(2, first.size());
    assertArrayEquals(DOCUMENT, first.get(0));
    assertNull(first.get(1));
    assertEquals(2, second.size());
  }

  @Test
  void reportsRejectedDocumentsOnceEverySubscriberIsNotified() {
    final RemoteConfigServiceImpl service =
        new RemoteConfigServiceImpl(mock(ConfigurationPoller.class));
    final IllegalArgumentException rejection = new IllegalArgumentException("malformed");
    final IllegalStateException otherRejection = new IllegalStateException("malformed too");
    final List<byte[]> received = new ArrayList<>();
    service.subscribe(
        content -> {
          throw rejection;
        });
    service.subscribe(received::add);
    service.subscribe(
        content -> {
          throw otherRejection;
        });

    final IllegalArgumentException reported =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.accept("key", DOCUMENT, mock(PollingRateHinter.class)));

    assertSame(rejection, reported);
    assertArrayEquals(new Throwable[] {otherRejection}, reported.getSuppressed());
    assertEquals(1, received.size());
    assertArrayEquals(DOCUMENT, received.get(0));
  }

  @Test
  void lateSubscribersRejectingTheCurrentDocumentStaySubscribed() throws Exception {
    final RemoteConfigServiceImpl service =
        new RemoteConfigServiceImpl(mock(ConfigurationPoller.class));
    service.accept("key", DOCUMENT, mock(PollingRateHinter.class));
    final List<byte[]> received = new ArrayList<>();

    final AutoCloseable subscription =
        service.subscribe(
            content -> {
              if (content != null) {
                throw new IllegalArgumentException("malformed");
              }
              received.add(null);
            });
    service.accept("key", null, mock(PollingRateHinter.class));
    subscription.close();

    assertEquals(1, received.size());
    assertNull(received.get(0));
  }

  @Test
  void replaysTheCurrentDocumentToLateSubscribers() {
    final RemoteConfigServiceImpl service =
        new RemoteConfigServiceImpl(mock(ConfigurationPoller.class));
    service.subscribe(content -> {});
    service.accept("key", DOCUMENT, mock(PollingRateHinter.class));

    final List<byte[]> late = new ArrayList<>();
    service.subscribe(late::add);

    assertEquals(1, late.size());
    assertArrayEquals(DOCUMENT, late.get(0));
  }

  @Test
  void concurrentRemovalIsDeliveredAfterTheInitialReplay() throws Exception {
    final RemoteConfigServiceImpl service =
        new RemoteConfigServiceImpl(mock(ConfigurationPoller.class));
    service.accept("key", DOCUMENT, null);
    final List<byte[]> received = new CopyOnWriteArrayList<>();
    final CountDownLatch replayStarted = new CountDownLatch(1);
    final CountDownLatch finishReplay = new CountDownLatch(1);
    final CountDownLatch removalStarted = new CountDownLatch(1);
    final ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      final Future<AutoCloseable> subscription =
          executor.submit(
              () ->
                  service.subscribe(
                      content -> {
                        if (content != null) {
                          replayStarted.countDown();
                          try {
                            assertTrue(finishReplay.await(5, SECONDS));
                          } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(e);
                          }
                        }
                        received.add(content);
                      }));
      assertTrue(replayStarted.await(5, SECONDS));
      final Future<?> removal =
          executor.submit(
              () -> {
                removalStarted.countDown();
                service.accept("key", null, null);
              });
      assertTrue(removalStarted.await(5, SECONDS));
      assertThrows(TimeoutException.class, () -> removal.get(100, MILLISECONDS));

      finishReplay.countDown();
      removal.get(5, SECONDS);
      subscription.get(5, SECONDS).close();

      assertEquals(2, received.size());
      assertArrayEquals(DOCUMENT, received.get(0));
      assertNull(received.get(1));
    } finally {
      finishReplay.countDown();
      executor.shutdownNow();
      service.close();
    }
  }

  @Test
  void startRegistersTheProductOnceBeforeAnySubscription() {
    final ConfigurationPoller poller = mock(ConfigurationPoller.class);
    final RemoteConfigServiceImpl service = new RemoteConfigServiceImpl(poller);

    service.start();
    service.start();
    service.subscribe(content -> {});

    verify(poller, times(1)).addCapabilities(Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES);
    verify(poller, times(1)).start();
  }

  @Test
  void unsubscribedSubscribersStopReceivingDocuments() throws Exception {
    final ConfigurationPoller poller = mock(ConfigurationPoller.class);
    final RemoteConfigServiceImpl service = new RemoteConfigServiceImpl(poller);
    final List<byte[]> received = new ArrayList<>();
    final AutoCloseable subscription = service.subscribe(received::add);

    subscription.close();
    service.accept("key", DOCUMENT, mock(PollingRateHinter.class));

    assertEquals(0, received.size());
    verify(poller, never()).removeListeners(any());
  }

  @Test
  void closeUnregistersTheProductAndDropsSubscribers() {
    final ConfigurationPoller poller = mock(ConfigurationPoller.class);
    final RemoteConfigServiceImpl service = new RemoteConfigServiceImpl(poller);
    final List<byte[]> received = new ArrayList<>();
    service.subscribe(received::add);

    service.close();
    service.close();
    service.accept("key", DOCUMENT, mock(PollingRateHinter.class));

    verify(poller, times(1))
        .removeCapabilities(Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES);
    verify(poller, times(1)).removeListeners(Product.FFE_FLAGS);
    verify(poller, never()).stop();
    assertEquals(0, received.size());
  }
}
