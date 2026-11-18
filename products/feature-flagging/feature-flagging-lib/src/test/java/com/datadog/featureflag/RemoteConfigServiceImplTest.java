package com.datadog.featureflag;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import datadog.remoteconfig.Capabilities;
import datadog.remoteconfig.ConfigurationDeserializer;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.PollingRateHinter;
import datadog.remoteconfig.Product;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

class RemoteConfigServiceImplTest {
  private static final byte[] DOCUMENT = "{\"flags\":{}}".getBytes(UTF_8);

  @Test
  void gatesDependentFlagsOnEverySubscriberAndRemovesOnClose() throws Exception {
    final ConfigurationPoller poller = mock(ConfigurationPoller.class);
    final RemoteConfigServiceImpl service = new RemoteConfigServiceImpl(poller);
    service.start();
    verify(poller, never())
        .addCapabilities(Capabilities.CAPABILITY_FFE_FLAG_DEPENDENT_FLAG_VARIANT_KEY_V1);
    final AutoCloseable supported =
        service.subscribe(content -> {}, Collections.singleton("dependent-flag-variant-key-v1"));
    verify(poller).addCapabilities(Capabilities.CAPABILITY_FFE_FLAG_DEPENDENT_FLAG_VARIANT_KEY_V1);
    final AutoCloseable legacy = service.subscribe(content -> {});
    verify(poller)
        .removeCapabilities(Capabilities.CAPABILITY_FFE_FLAG_DEPENDENT_FLAG_VARIANT_KEY_V1);
    legacy.close();
    verify(poller, times(2))
        .addCapabilities(Capabilities.CAPABILITY_FFE_FLAG_DEPENDENT_FLAG_VARIANT_KEY_V1);
    supported.close();
    verify(poller, times(2))
        .removeCapabilities(Capabilities.CAPABILITY_FFE_FLAG_DEPENDENT_FLAG_VARIANT_KEY_V1);
    verify(poller, times(4)).removeListeners(Product.FFE_FLAGS);
    service.close();
  }

  @Test
  void rejectsInFlightDocumentWhileRemovingDependentFlagCapability() throws Exception {
    final ConfigurationPoller poller = mock(ConfigurationPoller.class);
    final CountDownLatch removalStarted = new CountDownLatch(1);
    final CountDownLatch finishRemoval = new CountDownLatch(1);
    final AtomicBoolean blockRemoval = new AtomicBoolean(true);
    doAnswer(
            invocation -> {
              if (blockRemoval.compareAndSet(true, false)) {
                removalStarted.countDown();
                assertTrue(finishRemoval.await(5, SECONDS));
              }
              return null;
            })
        .when(poller)
        .removeCapabilities(Capabilities.CAPABILITY_FFE_FLAG_DEPENDENT_FLAG_VARIANT_KEY_V1);
    final RemoteConfigServiceImpl service = new RemoteConfigServiceImpl(poller);
    final List<byte[]> supportedDocuments = new CopyOnWriteArrayList<>();
    final List<byte[]> legacyDocuments = new CopyOnWriteArrayList<>();
    final AutoCloseable supported =
        service.subscribe(
            supportedDocuments::add, Collections.singleton("dependent-flag-variant-key-v1"));
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      final Future<AutoCloseable> legacy =
          executor.submit(() -> service.subscribe(legacyDocuments::add));
      assertTrue(removalStarted.await(5, SECONDS));

      assertThrows(
          IllegalStateException.class,
          () -> service.accept("key", DOCUMENT, mock(PollingRateHinter.class)));
      assertTrue(supportedDocuments.isEmpty());
      assertTrue(legacyDocuments.isEmpty());

      finishRemoval.countDown();
      legacy.get(5, SECONDS).close();
      service.accept("key", DOCUMENT, mock(PollingRateHinter.class));
      assertEquals(1, supportedDocuments.size());
    } finally {
      finishRemoval.countDown();
      executor.shutdownNow();
      supported.close();
      service.close();
    }
  }

  @Test
  void rejectsInFlightDocumentWhileStopping() throws Exception {
    final ConfigurationPoller poller = mock(ConfigurationPoller.class);
    final CountDownLatch stopStarted = new CountDownLatch(1);
    final CountDownLatch finishStop = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              stopStarted.countDown();
              assertTrue(finishStop.await(5, SECONDS));
              return null;
            })
        .when(poller)
        .stop();
    final RemoteConfigServiceImpl service = new RemoteConfigServiceImpl(poller);
    final List<byte[]> documents = new CopyOnWriteArrayList<>();
    service.subscribe(documents::add);
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      final Future<?> close = executor.submit(service::close);
      assertTrue(stopStarted.await(5, SECONDS));

      service.accept("key", DOCUMENT, mock(PollingRateHinter.class));
      assertTrue(documents.isEmpty());

      finishStop.countDown();
      close.get(5, SECONDS);
      service.subscribe(documents::add);
      assertTrue(documents.isEmpty());
    } finally {
      finishStop.countDown();
      executor.shutdownNow();
      service.close();
    }
  }

  @Test
  void retriesCleanupAfterFailedStartAndFailedRollback() {
    final ConfigurationPoller poller = mock(ConfigurationPoller.class);
    final AtomicBoolean failCleanup = new AtomicBoolean(true);
    doThrow(new IllegalStateException("start failure")).when(poller).start();
    doAnswer(
            invocation -> {
              if (failCleanup.compareAndSet(true, false)) {
                throw new IllegalStateException("cleanup failure");
              }
              return null;
            })
        .when(poller)
        .removeCapabilities(Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES);
    final RemoteConfigServiceImpl service = new RemoteConfigServiceImpl(poller);

    final IllegalStateException failure = assertThrows(IllegalStateException.class, service::start);
    assertEquals("start failure", failure.getMessage());
    assertEquals(1, failure.getSuppressed().length);

    service.close();

    verify(poller, times(2))
        .removeCapabilities(Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES);
    verify(poller).removeListeners(Product.FFE_FLAGS);
    verify(poller).stop();
  }

  @Test
  void concurrentStartRetriesAfterTheLifecycleOwnerFails() throws Exception {
    final ConfigurationPoller poller = mock(ConfigurationPoller.class);
    final CountDownLatch firstStartEntered = new CountDownLatch(1);
    final CountDownLatch releaseFirstStart = new CountDownLatch(1);
    final AtomicBoolean failFirstStart = new AtomicBoolean(true);
    doAnswer(
            invocation -> {
              if (failFirstStart.compareAndSet(true, false)) {
                firstStartEntered.countDown();
                assertTrue(releaseFirstStart.await(5, SECONDS));
                throw new IllegalStateException("start failure");
              }
              return null;
            })
        .when(poller)
        .start();
    final RemoteConfigServiceImpl service = new RemoteConfigServiceImpl(poller);
    final ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      final Future<?> first = executor.submit(service::start);
      assertTrue(firstStartEntered.await(5, SECONDS));
      final Future<?> second = executor.submit(service::start);
      second.get(5, SECONDS);

      releaseFirstStart.countDown();
      final ExecutionException failure =
          assertThrows(ExecutionException.class, () -> first.get(5, SECONDS));
      assertEquals(IllegalStateException.class, failure.getCause().getClass());

      verify(poller, times(2)).start();
      verify(poller).stop();
    } finally {
      releaseFirstStart.countDown();
      executor.shutdownNow();
      service.close();
    }
  }

  @Test
  void keepsDeliverySuspendedAcrossOverlappingCapabilityTransitions() throws Exception {
    final ConfigurationPoller poller = mock(ConfigurationPoller.class);
    final CountDownLatch additionStarted = new CountDownLatch(1);
    final CountDownLatch finishAddition = new CountDownLatch(1);
    final CountDownLatch removalStarted = new CountDownLatch(1);
    final CountDownLatch finishRemoval = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              additionStarted.countDown();
              assertTrue(finishAddition.await(5, SECONDS));
              return null;
            })
        .when(poller)
        .addCapabilities(Capabilities.CAPABILITY_FFE_FLAG_DEPENDENT_FLAG_VARIANT_KEY_V1);
    doAnswer(
            invocation -> {
              removalStarted.countDown();
              assertTrue(finishRemoval.await(5, SECONDS));
              return null;
            })
        .when(poller)
        .removeCapabilities(Capabilities.CAPABILITY_FFE_FLAG_DEPENDENT_FLAG_VARIANT_KEY_V1);
    final RemoteConfigServiceImpl service = new RemoteConfigServiceImpl(poller);
    final ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      final Future<AutoCloseable> supported =
          executor.submit(() ->
              service.subscribe(
                  content -> {}, Collections.singleton("dependent-flag-variant-key-v1")));
      assertTrue(additionStarted.await(5, SECONDS));
      final Future<AutoCloseable> legacy = executor.submit(() -> service.subscribe(content -> {}));
      final AutoCloseable legacyHandle = legacy.get(5, SECONDS);

      finishAddition.countDown();
      assertTrue(removalStarted.await(5, SECONDS));
      assertThrows(
          IllegalStateException.class,
          () -> service.accept("key", DOCUMENT, mock(PollingRateHinter.class)));

      finishRemoval.countDown();
      supported.get(5, SECONDS).close();
      legacyHandle.close();
    } finally {
      finishAddition.countDown();
      finishRemoval.countDown();
      executor.shutdownNow();
      service.close();
    }
  }

  @Test
  void rollsBackSubscriptionWhenCapabilityUpdateFails() throws Exception {
    final ConfigurationPoller poller = mock(ConfigurationPoller.class);
    final AtomicBoolean failRemoval = new AtomicBoolean(true);
    doAnswer(
            invocation -> {
              if (failRemoval.compareAndSet(true, false)) {
                throw new IllegalStateException("poller failure");
              }
              return null;
            })
        .when(poller)
        .removeCapabilities(Capabilities.CAPABILITY_FFE_FLAG_DEPENDENT_FLAG_VARIANT_KEY_V1);
    final RemoteConfigServiceImpl service = new RemoteConfigServiceImpl(poller);
    final List<byte[]> supportedDocuments = new ArrayList<>();
    final AutoCloseable supported =
        service.subscribe(
            supportedDocuments::add, Collections.singleton("dependent-flag-variant-key-v1"));

    assertThrows(IllegalStateException.class, () -> service.subscribe(content -> {}));
    service.accept("key", DOCUMENT, mock(PollingRateHinter.class));

    assertEquals(1, supportedDocuments.size());
    assertArrayEquals(DOCUMENT, supportedDocuments.get(0));
    supported.close();
    service.close();
  }

  @Test
  void rollsBackUnsubscriptionWhenCapabilityUpdateFails() throws Exception {
    final ConfigurationPoller poller = mock(ConfigurationPoller.class);
    final AtomicBoolean failAddition = new AtomicBoolean(true);
    doAnswer(
            invocation -> {
              if (failAddition.compareAndSet(true, false)) {
                throw new IllegalStateException("poller failure");
              }
              return null;
            })
        .when(poller)
        .addCapabilities(Capabilities.CAPABILITY_FFE_FLAG_DEPENDENT_FLAG_VARIANT_KEY_V1);
    final RemoteConfigServiceImpl service = new RemoteConfigServiceImpl(poller);
    final List<byte[]> legacyDocuments = new ArrayList<>();
    final List<byte[]> supportedDocuments = new ArrayList<>();
    final AutoCloseable legacy = service.subscribe(legacyDocuments::add);
    final AutoCloseable supported =
        service.subscribe(
            supportedDocuments::add, Collections.singleton("dependent-flag-variant-key-v1"));

    assertThrows(IllegalStateException.class, legacy::close);
    service.accept("key", DOCUMENT, mock(PollingRateHinter.class));

    assertEquals(1, legacyDocuments.size());
    assertEquals(1, supportedDocuments.size());
    legacy.close();
    supported.close();
    service.close();
  }

  @Test
  void callbackSubscriptionDoesNotDeadlockCapabilityTransition() throws Exception {
    final ConfigurationPoller poller = mock(ConfigurationPoller.class);
    final CountDownLatch callbackStarted = new CountDownLatch(1);
    final CountDownLatch finishCallback = new CountDownLatch(1);
    final CountDownLatch transitionStarted = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              transitionStarted.countDown();
              synchronized (poller) {
                return null;
              }
            })
        .when(poller)
        .addCapabilities(Capabilities.CAPABILITY_FFE_FLAG_DEPENDENT_FLAG_VARIANT_KEY_V1);
    final RemoteConfigServiceImpl service = new RemoteConfigServiceImpl(poller);
    final AutoCloseable legacy = service.subscribe(content -> {});
    final AtomicReference<AutoCloseable> callbackSubscription = new AtomicReference<>();
    service.subscribe(
        content -> {
          callbackStarted.countDown();
          try {
            assertTrue(finishCallback.await(5, SECONDS));
            callbackSubscription.set(service.subscribe(ignored -> {}));
          } catch (final Exception e) {
            throw new AssertionError(e);
          }
        },
        Collections.singleton("dependent-flag-variant-key-v1"));
    final ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      final Future<?> delivery =
          executor.submit(() -> {
            synchronized (poller) {
              service.accept("key", DOCUMENT, mock(PollingRateHinter.class));
            }
          });
      assertTrue(callbackStarted.await(5, SECONDS));
      final Future<?> transition =
          executor.submit(() -> {
            try {
              legacy.close();
            } catch (final Exception e) {
              throw new AssertionError(e);
            }
          });
      assertTrue(transitionStarted.await(5, SECONDS));

      finishCallback.countDown();
      delivery.get(5, SECONDS);
      transition.get(5, SECONDS);
      callbackSubscription.get().close();
    } finally {
      finishCallback.countDown();
      executor.shutdownNow();
      service.close();
    }
  }

  @Test
  void unknownEvaluatorCapabilitiesDoNotOptIn() {
    final ConfigurationPoller poller = mock(ConfigurationPoller.class);
    final RemoteConfigServiceImpl service = new RemoteConfigServiceImpl(poller);
    service.subscribe(content -> {}, Collections.singleton("unknown-v1"));
    verify(poller, never())
        .addCapabilities(Capabilities.CAPABILITY_FFE_FLAG_DEPENDENT_FLAG_VARIANT_KEY_V1);
    service.close();
  }

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
    service.subscribe(content -> {
      throw rejection;
    });
    service.subscribe(received::add);
    service.subscribe(content -> {
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
        service.subscribe(content -> {
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
  @SuppressWarnings("unchecked")
  void requestsADocumentReceivedBeforeAnySubscriberAgainWhenRejected() {
    final ConfigurationPoller poller = mock(ConfigurationPoller.class);
    final RemoteConfigServiceImpl service = new RemoteConfigServiceImpl(poller);
    service.start();
    service.accept("key", DOCUMENT, mock(PollingRateHinter.class));

    service.subscribe(content -> {
      throw new IllegalArgumentException("malformed");
    });
    service.subscribe(content -> {
      throw new IllegalArgumentException("malformed");
    });

    verify(poller, times(1)).removeListeners(Product.FFE_FLAGS);
    verify(poller, times(2))
        .addListener(eq(Product.FFE_FLAGS), any(ConfigurationDeserializer.class), eq(service));
  }

  @Test
  void keepsADocumentReceivedBeforeAnySubscriberWhenAccepted() {
    final ConfigurationPoller poller = mock(ConfigurationPoller.class);
    final RemoteConfigServiceImpl service = new RemoteConfigServiceImpl(poller);
    service.start();
    service.accept("key", DOCUMENT, mock(PollingRateHinter.class));

    service.subscribe(content -> {});

    verify(poller, never()).removeListeners(any());
  }

  @Test
  void doesNotRequestAgainADocumentAlreadyRejectedOnDelivery() {
    final ConfigurationPoller poller = mock(ConfigurationPoller.class);
    final RemoteConfigServiceImpl service = new RemoteConfigServiceImpl(poller);
    service.subscribe(content -> {
      throw new IllegalArgumentException("malformed");
    });
    assertThrows(
        IllegalArgumentException.class,
        () -> service.accept("key", DOCUMENT, mock(PollingRateHinter.class)));

    service.subscribe(content -> {
      throw new IllegalArgumentException("malformed");
    });

    verify(poller, never()).removeListeners(any());
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
          executor.submit(() ->
              service.subscribe(content -> {
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
          executor.submit(() -> {
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
    verify(poller, times(1)).stop();
    assertEquals(0, received.size());
  }

  @Test
  void restartsBalancePollerReferences() {
    final ConfigurationPoller poller = mock(ConfigurationPoller.class);
    final RemoteConfigServiceImpl service = new RemoteConfigServiceImpl(poller);

    service.start();
    service.close();
    service.subscribe(content -> {});
    service.close();

    verify(poller, times(2)).start();
    verify(poller, times(2)).stop();
  }

  @Nested
  class Diagnostics {
    private final Logger logger = (Logger) LoggerFactory.getLogger(RemoteConfigServiceImpl.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;
    private RemoteConfigServiceImpl service;

    @BeforeEach
    void captureLogs() {
      previousLevel = logger.getLevel();
      logger.setLevel(Level.DEBUG);
      appender.start();
      logger.addAppender(appender);
      service = new RemoteConfigServiceImpl(mock(ConfigurationPoller.class));
    }

    @AfterEach
    void restoreLogging() {
      logger.detachAppender(appender);
      appender.stop();
      logger.setLevel(previousLevel);
    }

    @Test
    void reportsLifecycle() {
      service.start();
      service.close();

      assertLogged("starting remote_config configuration source");
      assertLogged("remote_config configuration source stopped");
    }

    @Test
    void reportsReceivedConfiguration() {
      service.accept("config", DOCUMENT, mock(PollingRateHinter.class));

      assertLogged("configuration received: key=config, bytes=" + DOCUMENT.length);
    }

    @Test
    void reportsRemovedConfiguration() {
      service.accept("config", null, mock(PollingRateHinter.class));

      assertLogged("configuration removed: key=config");
    }

    @Test
    void reportsTheDocumentOnlyOnceItIsDelivered() {
      final AtomicBoolean loggedBeforeDelivery = new AtomicBoolean(true);
      service.subscribe(content ->
          loggedBeforeDelivery.set(
              appender.list.stream()
                  .anyMatch(e -> e.getFormattedMessage().contains("configuration received"))));

      service.accept("config", DOCUMENT, mock(PollingRateHinter.class));

      assertFalse(loggedBeforeDelivery.get(), "The log must not delay the delivery");
      assertLogged("configuration received: key=config, bytes=" + DOCUMENT.length);
    }

    private void assertLogged(final String text) {
      assertTrue(
          appender.list.stream().anyMatch(e -> e.getFormattedMessage().contains(text)),
          "Missing log: " + text);
    }
  }
}
