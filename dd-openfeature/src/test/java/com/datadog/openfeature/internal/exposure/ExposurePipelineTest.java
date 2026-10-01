package com.datadog.openfeature.internal.exposure;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.openfeature.internal.JsonReading;
import com.datadog.openfeature.internal.RuntimeServices;
import com.datadog.openfeature.internal.connector.EventTransport;
import com.datadog.openfeature.internal.connector.HealthMetrics;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class ExposurePipelineTest {
  private static final long TIMEOUT_NANOS = SECONDS.toNanos(5);

  private final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
  private final AtomicInteger posts = new AtomicInteger();
  private volatile int failingPost = -1;

  private final EventTransport transport =
      (route, json) -> {
        assertEquals("exposures", route);
        if (this.posts.incrementAndGet() == this.failingPost) {
          throw new IOException("ambiguous timeout");
        }
        this.requests.add(JsonReading.readObject(json));
      };

  @TableTest({
    "service        | env    | version",
    "               |        |        ",
    "'test-service' | 'test' | '23'   ",
    "'test-service' |        | '23'   ",
    "'test-service' | 'test' |        "
  })
  void testExposureEventWrites(final String service, final String env, final String version)
      throws Exception {
    final Map<String, String> context = new HashMap<>();
    putIfNotNull(context, "service", service);
    putIfNotNull(context, "env", env);
    putIfNotNull(context, "version", version);
    final List<ExposureEvent> exposures = buildExposures(5);

    try (ExposurePipeline pipeline = pipeline(context)) {
      pipeline.start();
      exposures.forEach(pipeline);

      eventually(() -> allExposures().size() == exposures.size());
    }

    for (final Map<String, Object> request : this.requests) {
      assertEquals(context, request.get("context"));
    }
    assertEquals(keys(exposures), exposureKeys(allExposures()));
  }

  @Test
  void serializesExposureWireShape() throws Exception {
    final ExposureEvent exposure =
        new ExposureEvent(
            1234L,
            new Allocation("allocation"),
            new Flag("flag"),
            new Variant("variant"),
            new Subject("subject", singletonMap("tier", "gold")),
            7);

    try (ExposurePipeline pipeline = pipeline(emptyMap())) {
      pipeline.start();
      pipeline.accept(exposure);

      eventually(() -> !allExposures().isEmpty());
    }

    final Map<String, Object> json = allExposures().get(0);
    assertEquals(1234L, json.get("timestamp"));
    assertEquals(singletonMap("key", "allocation"), json.get("allocation"));
    assertEquals(singletonMap("key", "flag"), json.get("flag"));
    assertEquals(singletonMap("key", "variant"), json.get("variant"));
    final Map<String, Object> subject = new HashMap<>();
    subject.put("id", "subject");
    subject.put("attributes", singletonMap("tier", "gold"));
    assertEquals(subject, json.get("subject"));
    assertEquals(7L, json.get("serial_id"));
  }

  @Test
  void testLruCache() throws Exception {
    final List<ExposureEvent> exposures = buildExposures(6);

    try (ExposurePipeline pipeline = pipeline(emptyMap())) {
      pipeline.start();
      // populating the cache
      exposures.forEach(pipeline);

      // all events are written
      eventually(() -> allExposures().size() == exposures.size());

      // publishing duplicate events
      exposures.forEach(pipeline);

      // no events are written
      MILLISECONDS.sleep(300); // wait until a flush happens
      assertEquals(exposures.size(), allExposures().size());

      // a new event is generated
      pipeline.accept(buildExposure());

      // oldest event is evicted and the new one is submitted
      eventually(() -> allExposures().size() == exposures.size() + 1);
    }
  }

  @Test
  void testHighLoadScenario() throws Exception {
    final int exposuresPerThread = 100;
    final int threads = Runtime.getRuntime().availableProcessors();
    final ExecutorService executor = Executors.newFixedThreadPool(threads);
    final List<ExposureEvent> exposures = buildExposures(threads * exposuresPerThread);
    final CountDownLatch latch = new CountDownLatch(1);

    try (ExposurePipeline pipeline =
        new ExposurePipeline(
            ExposurePipeline.DEFAULT_CAPACITY,
            100,
            MILLISECONDS,
            this.transport,
            emptyMap(),
            new RuntimeServices(HealthMetrics.NOOP))) {
      pipeline.start();
      final List<Future<Boolean>> futures = new ArrayList<>();
      for (int index = 0; index < exposures.size(); index += exposuresPerThread) {
        final List<ExposureEvent> partition =
            exposures.subList(index, Math.min(index + exposuresPerThread, exposures.size()));
        futures.add(
            executor.submit(
                () -> {
                  latch.await();
                  partition.forEach(pipeline);
                  return true;
                }));
      }
      latch.countDown(); // start threads
      for (final Future<Boolean> future : futures) {
        assertTrue(future.get()); // wait for all threads to finish
      }

      eventually(() -> allExposures().size() == exposures.size());
      assertEquals(keys(exposures), exposureKeys(allExposures()));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void testAmbiguousExposureBatchIsNotRetried() throws Exception {
    this.failingPost = 1;
    final List<ExposureEvent> exposures = buildExposures(2);

    try (ExposurePipeline pipeline = pipeline(emptyMap())) {
      pipeline.start();
      pipeline.accept(exposures.get(0));
      eventually(() -> this.posts.get() == 1);
      MILLISECONDS.sleep(300);
      assertEquals(1, this.posts.get());

      pipeline.accept(exposures.get(1));
      eventually(() -> !allExposures().isEmpty());
    }

    assertEquals(keys(exposures.subList(1, 2)), exposureKeys(allExposures()));
  }

  @Test
  void testNonFiniteAttributesDoNotPoisonFollowingExposures() throws Exception {
    final ExposureEvent invalid = buildExposure(singletonMap("invalid", Double.NaN));
    final ExposureEvent valid = buildExposure();

    try (ExposurePipeline pipeline = pipeline(emptyMap())) {
      pipeline.start();
      pipeline.accept(invalid);
      pipeline.accept(valid);

      eventually(() -> allExposures().size() == 2);
    }

    assertEquals(keys(List.of(invalid, valid)), exposureKeys(allExposures()));
  }

  @Test
  void closeStopsTheSerializer() throws Exception {
    final ExposurePipeline pipeline = pipeline(emptyMap());
    pipeline.start();
    assertTrue(pipeline.isSerializerThreadAlive());

    pipeline.close();

    eventually(() -> !pipeline.isSerializerThreadAlive());
    assertFalse(pipeline.isSerializerThreadAlive());
  }

  private ExposurePipeline pipeline(final Map<String, String> context) {
    return new ExposurePipeline(
        1 << 4,
        100,
        MILLISECONDS,
        this.transport,
        context,
        new RuntimeServices(HealthMetrics.NOOP));
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> allExposures() {
    final List<Map<String, Object>> exposures = new ArrayList<>();
    for (final Map<String, Object> request : this.requests) {
      exposures.addAll((List<Map<String, Object>>) request.get("exposures"));
    }
    return exposures;
  }

  private static Set<String> keys(final List<ExposureEvent> exposures) {
    final Set<String> keys = new HashSet<>();
    for (final ExposureEvent exposure : exposures) {
      keys.add(exposure.flag.key + "/" + exposure.subject.id);
    }
    return keys;
  }

  @SuppressWarnings("unchecked")
  private static Set<String> exposureKeys(final List<Map<String, Object>> exposures) {
    final Set<String> keys = new HashSet<>();
    for (final Map<String, Object> exposure : exposures) {
      keys.add(
          ((Map<String, Object>) exposure.get("flag")).get("key")
              + "/"
              + ((Map<String, Object>) exposure.get("subject")).get("id"));
    }
    return keys;
  }

  private static List<ExposureEvent> buildExposures(final int size) {
    final List<ExposureEvent> exposures = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      exposures.add(buildExposure());
    }
    return exposures;
  }

  private static ExposureEvent buildExposure() {
    return buildExposure(emptyMap());
  }

  private static ExposureEvent buildExposure(final Map<String, Object> attributes) {
    return new ExposureEvent(
        System.currentTimeMillis(),
        new Allocation(UUID.randomUUID().toString()),
        new Flag(UUID.randomUUID().toString()),
        new Variant(UUID.randomUUID().toString()),
        new Subject(UUID.randomUUID().toString(), attributes),
        null);
  }

  private static void putIfNotNull(
      final Map<String, String> map, final String key, final String value) {
    if (value != null) {
      map.put(key, value);
    }
  }

  private static void eventually(final BooleanSupplier condition) throws InterruptedException {
    final long deadline = System.nanoTime() + TIMEOUT_NANOS;
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("Condition not met in time");
      }
      MILLISECONDS.sleep(10);
    }
  }
}
