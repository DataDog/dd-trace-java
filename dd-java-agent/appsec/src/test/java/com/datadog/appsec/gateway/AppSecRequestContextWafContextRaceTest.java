package com.datadog.appsec.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.appsec.ddwaf.WafInitialization;
import com.datadog.ddwaf.Waf;
import com.datadog.ddwaf.WafBuilder;
import com.datadog.ddwaf.WafContext;
import com.datadog.ddwaf.WafHandle;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import okio.Okio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Contract and concurrency tests for the {@code getOrCreateWafContext}/{@code closeWafContext}
 * lifecycle, guarding against the TOCTOU race that used to resurrect an orphaned native {@link
 * WafContext} on already-closed requests (APPSEC-69085).
 */
class AppSecRequestContextWafContextRaceTest {

  private static final JsonAdapter<Map<String, Object>> ADAPTER =
      new Moshi.Builder()
          .build()
          .adapter(Types.newParameterizedType(Map.class, String.class, Object.class));

  private WafBuilder wafBuilder;
  private WafHandle wafHandle;

  @BeforeEach
  void setup() throws Exception {
    // Force the native library to load and initialize.
    assertTrue(WafInitialization.ONLINE, "libddwaf must be available for this test");
    Waf.initialize(false);
    wafBuilder = new WafBuilder();
    try (InputStream stream =
        getClass().getClassLoader().getResourceAsStream("test_multi_config.json")) {
      wafBuilder.addOrUpdateConfig("test", ADAPTER.fromJson(Okio.buffer(Okio.source(stream))));
    }
    wafHandle = wafBuilder.buildWafHandleInstance();
  }

  @AfterEach
  void tearDown() {
    if (wafBuilder != null) {
      wafBuilder.close();
    }
  }

  @Test
  void firstUseCreatesContext() {
    AppSecRequestContext ctx = new AppSecRequestContext();
    try {
      WafContext created = ctx.getOrCreateWafContext(wafHandle, false, false);
      assertNotNull(created);
      assertTrue(created.isOnline());
      assertFalse(ctx.isWafContextClosed());
    } finally {
      ctx.closeWafContext();
    }
  }

  @Test
  void reuseBeforeCloseReturnsSameInstance() {
    AppSecRequestContext ctx = new AppSecRequestContext();
    try {
      WafContext first = ctx.getOrCreateWafContext(wafHandle, false, false);
      WafContext second = ctx.getOrCreateWafContext(wafHandle, false, false);
      assertNotNull(first);
      assertSame(first, second);
    } finally {
      ctx.closeWafContext();
    }
  }

  @Test
  void rejectsCreationAfterClose() {
    AppSecRequestContext ctx = new AppSecRequestContext();
    WafContext created = ctx.getOrCreateWafContext(wafHandle, false, false);
    assertNotNull(created);

    ctx.closeWafContext();
    assertTrue(ctx.isWafContextClosed());
    assertFalse(created.isOnline());

    // A late/async caller must not resurrect a brand-new orphaned context.
    WafContext afterClose = ctx.getOrCreateWafContext(wafHandle, false, false);
    assertNull(afterClose);
    assertTrue(ctx.isWafContextClosed());
  }

  /**
   * Drives {@code closeWafContext()} and {@code getOrCreateWafContext()} concurrently via a barrier
   * across many iterations. The invariant: whatever {@code getOrCreateWafContext} returns after the
   * race is either the ORIGINAL context (same instance, still online) or {@code null} - never a
   * second/different instance. At the end, no observed context may still be online (all created
   * contexts were eventually closed - no orphans).
   */
  @Test
  void concurrentCloseNeverCreatesOrphan() throws Exception {
    final int iterations = 5_000;
    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<WafContext> observed = new ArrayList<>(iterations);
    try {
      for (int i = 0; i < iterations; i++) {
        final AppSecRequestContext ctx = new AppSecRequestContext();
        WafContext original = ctx.getOrCreateWafContext(wafHandle, false, false);
        assertNotNull(original);
        observed.add(original);

        final CyclicBarrier barrier = new CyclicBarrier(2);
        Future<?> closer =
            pool.submit(
                () -> {
                  barrier.await();
                  ctx.closeWafContext();
                  return null;
                });
        Future<WafContext> creator =
            pool.submit(
                () -> {
                  barrier.await();
                  return ctx.getOrCreateWafContext(wafHandle, false, false);
                });

        closer.get();
        WafContext late = creator.get();

        // Never a freshly created second context: only the original or null.
        if (late != null) {
          assertSame(original, late, "getOrCreateWafContext resurrected a new orphan context");
        }
        assertTrue(ctx.isWafContextClosed());
      }
    } finally {
      pool.shutdownNow();
    }

    // Global invariant: every created context was eventually closed - no orphan left online.
    for (WafContext context : observed) {
      assertFalse(context.isOnline(), "orphaned WafContext left online (never closed)");
    }
  }
}
