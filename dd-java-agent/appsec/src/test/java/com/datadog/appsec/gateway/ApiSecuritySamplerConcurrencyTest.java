package com.datadog.appsec.gateway;

import static java.util.Arrays.asList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.appsec.api.security.ApiSecuritySamplerImpl;
import datadog.trace.api.time.ControllableTimeSource;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

@WithConfig(key = "apm.tracing.enabled", value = "false")
class ApiSecuritySamplerConcurrencyTest extends DDJavaSpecification {
  private static final String FRAMEWORK = "test-framework";
  private static final long EXPIRATION_MS = 10;
  private static final int WORKERS = 4;

  @Test
  void concurrentStandaloneRequestsSelectOnlyOneSamplePerEndpoint() throws Exception {
    ControllableTimeSource time = new ControllableTimeSource();
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl(8, EXPIRATION_MS, time);
    ExecutorService executor = executor(WORKERS);
    try {
      for (int round = 0; round < 200; round++) {
        CountDownLatch ready = new CountDownLatch(WORKERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> decisions = new ArrayList<>();
        for (int worker = 0; worker < WORKERS; worker++) {
          decisions.add(
              executor.submit(
                  () -> {
                    ready.countDown();
                    assertTrue(start.await(5, SECONDS));
                    AppSecRequestContext ctx = context("same-endpoint");
                    try {
                      return sampler.preSampleRequest(ctx, FRAMEWORK);
                    } finally {
                      if (ctx.isKeepOpenForApiSecurityPostProcessing()) {
                        sampler.releaseOne();
                      }
                    }
                  }));
        }
        assertTrue(ready.await(5, SECONDS));
        start.countDown();
        int sampled = 0;
        for (Future<Boolean> decision : decisions) {
          if (decision.get(5, SECONDS)) {
            sampled++;
          }
        }
        assertEquals(1, sampled);
        assertConsistent(sampler, 8);
        assertEquals(1, map(sampler).size());
        time.advance(EXPIRATION_MS * 1_000_000);
      }
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, SECONDS));
    }
  }

  @Test
  void concurrentExpirationAndCapacityEvictionKeepStateConsistent() throws Exception {
    ControllableTimeSource time = new ControllableTimeSource();
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl(8, EXPIRATION_MS, time);
    ExecutorService executor = executor(WORKERS);
    try {
      for (int round = 0; round < 50; round++) {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();
        for (int worker = 0; worker < WORKERS; worker++) {
          final int offset = worker;
          tasks.add(
              executor.submit(
                  () -> {
                    assertTrue(start.await(5, SECONDS));
                    for (int request = 0; request < 100; request++) {
                      AppSecRequestContext ctx = context("endpoint-" + ((request + offset) % 16));
                      try {
                        sampler.preSampleRequest(ctx, FRAMEWORK);
                      } finally {
                        if (ctx.isKeepOpenForApiSecurityPostProcessing()) {
                          sampler.releaseOne();
                        }
                      }
                    }
                    return null;
                  }));
        }
        start.countDown();
        for (Future<?> task : tasks) {
          task.get(5, SECONDS);
        }
        assertConsistent(sampler, 8);
        time.advance(EXPIRATION_MS * 1_000_000);
      }
      // All four permits remain usable after concurrent cleanup.
      for (int i = 0; i < WORKERS; i++) {
        assertTrue(sampler.preSampleRequest(context("final-" + i), FRAMEWORK));
      }
      assertFalse(sampler.preSampleRequest(context("no-permit"), FRAMEWORK));
      for (int i = 0; i < WORKERS; i++) {
        sampler.releaseOne();
      }
      assertTrue(sampler.preSampleRequest(context("after-release"), FRAMEWORK));
      sampler.releaseOne();
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, SECONDS));
    }
  }

  @Test
  void refreshedEndpointMovesAfterOlderEntriesForExpirationAndEviction() throws Exception {
    ControllableTimeSource time = new ControllableTimeSource();
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl(2, EXPIRATION_MS, time);
    AppSecRequestContext first = context("first");
    sample(sampler, first);
    time.advance(5_000_000);
    AppSecRequestContext second = context("second");
    sample(sampler, second);
    time.advance(5_000_000);
    sample(sampler, context("first"));
    time.advance(5_000_000);
    AppSecRequestContext third = context("third");
    sample(sampler, third);

    assertFalse(map(sampler).containsKey(second.getApiSecurityEndpointHash()));
    assertEquals(
        asList(first.getApiSecurityEndpointHash(), third.getApiSecurityEndpointHash()),
        new ArrayList<>(deque(sampler)));

    AppSecRequestContext fourth = context("fourth");
    sample(sampler, fourth);
    assertFalse(map(sampler).containsKey(first.getApiSecurityEndpointHash()));
    assertEquals(
        asList(third.getApiSecurityEndpointHash(), fourth.getApiSecurityEndpointHash()),
        new ArrayList<>(deque(sampler)));
    assertFalse(sampler.preSampleRequest(context("third"), FRAMEWORK));
    assertConsistent(sampler, 2);
  }

  private static void sample(ApiSecuritySamplerImpl sampler, AppSecRequestContext ctx) {
    assertTrue(sampler.preSampleRequest(ctx, FRAMEWORK));
    try {
      assertTrue(sampler.sampleRequest(ctx));
    } finally {
      sampler.releaseOne();
    }
  }

  private static void assertConsistent(ApiSecuritySamplerImpl sampler, int capacity)
      throws Exception {
    Map<Long, Long> map = map(sampler);
    Deque<Long> deque = deque(sampler);
    assertTrue(map.size() <= capacity);
    assertEquals(map.size(), deque.size(), "No duplicate or orphaned queue entries");
    assertEquals(map.keySet(), new HashSet<>(deque));
  }

  private static AppSecRequestContext context(String route) {
    AppSecRequestContext ctx = new AppSecRequestContext();
    ctx.setRoute(route);
    ctx.setMethod("GET");
    ctx.setResponseStatus(200);
    return ctx;
  }

  private static ExecutorService executor(int threads) {
    return Executors.newFixedThreadPool(
        threads,
        task -> {
          Thread thread = new Thread(task, "api-security-sampler-test");
          thread.setDaemon(true);
          return thread;
        });
  }

  @SuppressWarnings("unchecked")
  private static Map<Long, Long> map(ApiSecuritySamplerImpl sampler) throws Exception {
    return (Map<Long, Long>) field(sampler, "accessMap");
  }

  @SuppressWarnings("unchecked")
  private static Deque<Long> deque(ApiSecuritySamplerImpl sampler) throws Exception {
    return (Deque<Long>) field(sampler, "accessDeque");
  }

  private static Object field(ApiSecuritySamplerImpl sampler, String name) throws Exception {
    Field field = ApiSecuritySamplerImpl.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(sampler);
  }
}
