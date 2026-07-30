package datadog.openfeature.internal.core;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ConfigurationStoreTest {

  @Test
  void preservesLastKnownGoodAfterRejectedPayload() {
    final ConfigurationStore store = new ConfigurationStore();
    final AtomicInteger changes = new AtomicInteger();
    store.addListener(ignored -> changes.incrementAndGet());
    assertEquals(ApplyResult.ACCEPTED, store.apply(Fixtures.UFC.getBytes(UTF_8)));
    final ConfigurationSnapshot accepted = store.current();

    assertEquals(ApplyResult.REJECTED, store.apply("{".getBytes(UTF_8)));
    assertSame(accepted, store.current());
    assertEquals(1, changes.get());

    assertEquals(
        ApplyResult.ACCEPTED,
        store.apply(Fixtures.UFC.replace("hello", "recovered").getBytes(UTF_8)));
    assertEquals(2, changes.get());
    assertTrue(store.hasConfiguration());
  }

  @Test
  void clearsConfigurationExplicitly() {
    final ConfigurationStore store = new ConfigurationStore();
    store.apply(Fixtures.UFC.getBytes(UTF_8));

    assertEquals(ApplyResult.CLEARED, store.clear());
    assertEquals(null, store.current());
    assertFalse(store.hasConfiguration());
  }

  @Test
  void sendsCurrentAndDeletedSnapshotsToListeners() {
    final ConfigurationStore store = new ConfigurationStore();
    store.apply(Fixtures.UFC.getBytes(UTF_8));
    final AtomicReference<ConfigurationSnapshot> current = new AtomicReference<>();
    final Consumer<ConfigurationSnapshot> listener = current::set;

    store.addListener(listener);
    assertSame(store.current(), current.get());

    store.clear();
    assertEquals(null, current.get());
    store.removeListener(listener);
  }

  @Test
  void waitsForConfigurationAndTimesOut() throws Exception {
    final ConfigurationStore store = new ConfigurationStore();
    assertFalse(store.awaitConfiguration(1, TimeUnit.MILLISECONDS));
    final CountDownLatch waiting = new CountDownLatch(1);
    final AtomicReference<Boolean> result = new AtomicReference<>();
    final Thread thread =
        new Thread(
            () -> {
              waiting.countDown();
              try {
                result.set(store.awaitConfiguration(1, TimeUnit.SECONDS));
              } catch (final InterruptedException error) {
                Thread.currentThread().interrupt();
              }
            });
    thread.start();
    assertTrue(waiting.await(1, TimeUnit.SECONDS));

    store.apply(Fixtures.UFC.getBytes(UTF_8));
    thread.join(1_000);

    assertEquals(true, result.get());
    assertTrue(store.awaitConfiguration(0, TimeUnit.MILLISECONDS));
  }
}
