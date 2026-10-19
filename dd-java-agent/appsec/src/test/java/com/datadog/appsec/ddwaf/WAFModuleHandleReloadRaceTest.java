package com.datadog.appsec.ddwaf;

import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datadog.appsec.config.AppSecModuleConfigurer;
import com.datadog.appsec.config.TraceSegmentPostProcessor;
import com.datadog.appsec.event.ChangeableFlow;
import com.datadog.appsec.event.DataListener;
import com.datadog.appsec.event.data.MapDataBundle;
import com.datadog.appsec.gateway.AppSecRequestContext;
import com.datadog.appsec.gateway.GatewayContext;
import com.datadog.ddwaf.Waf;
import com.datadog.ddwaf.WafBuilder;
import com.datadog.ddwaf.WafContext;
import com.datadog.ddwaf.WafHandle;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.api.telemetry.RuleType;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.StampedLock;
import okio.Okio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests handle replacement against context creation without accessing retired native pointers. */
class WAFModuleHandleReloadRaceTest {
  private WafHandle initialHandle;
  private WafBuilder builder;
  private WAFModule module;
  private final AppSecRequestContext request = mock(AppSecRequestContext.class);
  private final WafContext context = mock(WafContext.class);
  private AppSecModuleConfigurer.SubconfigListener listener;
  private DataListener callback;

  @BeforeEach
  void setup() throws Exception {
    assertTrue(WafInitialization.ONLINE);
    builder = new WafBuilder();
    try (InputStream stream = getClass().getResourceAsStream("/test_multi_config.json")) {
      Map<String, Object> config =
          new Moshi.Builder()
              .build()
              .<Map<String, Object>>adapter(
                  Types.newParameterizedType(Map.class, String.class, Object.class))
              .fromJson(Okio.buffer(Okio.source(stream)));
      builder.addOrUpdateConfig("test", config);
    }
    when(context.run(anyMap(), any(), any())).thenReturn(Waf.ResultWithData.OK_NULL);
    module = new WAFModule();
    module.setWafBuilder(builder);
    module.config(
        new AppSecModuleConfigurer() {
          @Override
          public void addSubConfigListener(
              String key, AppSecModuleConfigurer.SubconfigListener value) {
            listener = value;
          }

          @Override
          public void addTraceSegmentPostProcessor(TraceSegmentPostProcessor interceptor) {}
        });
    callback = module.getDataSubscriptions().iterator().next();
    initialHandle = currentHandle();
  }

  private Object currentSnapshotField(String name) throws Exception {
    Field referenceField = WAFModule.class.getDeclaredField("ctxAndAddresses");
    referenceField.setAccessible(true);
    Object snapshot = ((AtomicReference<?>) referenceField.get(module)).get();
    Field field = snapshot.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(snapshot);
  }

  private WafHandle currentHandle() throws Exception {
    return (WafHandle) currentSnapshotField("ctx");
  }

  @AfterEach
  void cleanup() throws Exception {
    if (module != null) {
      currentHandle().close();
    }
    if (builder != null) {
      builder.close();
    }
  }

  private void reload() throws Exception {
    listener.onNewSubconfig(null, AppSecModuleConfigurer.Reconfiguration.NOOP);
  }

  private void evaluate() {
    evaluate(request, new GatewayContext(false));
  }

  private void evaluate(AppSecRequestContext requestContext, GatewayContext gatewayContext) {
    callback.onDataAvailable(
        new ChangeableFlow(), requestContext, MapDataBundle.ofDelegate(emptyMap()), gatewayContext);
  }

  @Test
  void usesReplacementWhenReloadRetiresTheCapturedHandle() throws Exception {
    when(request.isWafContextClosed())
        .thenAnswer(
            invocation -> {
              reload();
              return false;
            });
    when(request.getOrCreateWafContext(any(), anyBoolean(), anyBoolean())).thenReturn(context);

    evaluate();

    verify(request)
        .getOrCreateWafContext(
            argThat(handle -> handle != initialHandle && handle.isOnline()),
            anyBoolean(),
            anyBoolean());
    verify(request, never()).getOrCreateWafContext(eq(initialHandle), anyBoolean(), anyBoolean());
    verify(context).run(anyMap(), any(), any());
  }

  @Test
  void waitsForContextCreationBeforeClosingTheHandle() throws Exception {
    CountDownLatch creating = new CountDownLatch(1);
    CountDownLatch finishCreation = new CountDownLatch(1);
    when(request.getOrCreateWafContext(any(), anyBoolean(), anyBoolean()))
        .thenAnswer(
            invocation -> {
              creating.countDown();
              assertTrue(finishCreation.await(10, TimeUnit.SECONDS));
              return context;
            });
    FutureTask<Void> evaluation =
        new FutureTask<>(
            () -> {
              evaluate();
              return null;
            });
    FutureTask<Void> update =
        new FutureTask<>(
            () -> {
              reload();
              return null;
            });
    Thread evaluationThread = new Thread(evaluation, "waf-context-creation-test");
    Thread updateThread = new Thread(update, "waf-handle-reload-test");
    evaluationThread.start();
    try {
      assertTrue(creating.await(10, TimeUnit.SECONDS));
      updateThread.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (!update.isDone()
          && !blockedInHandleReplacement(updateThread)
          && System.nanoTime() < deadline) {
        Thread.yield();
      }
      assertFalse(update.isDone(), "reload must wait for context creation");
      assertTrue(blockedInHandleReplacement(updateThread));
      assertTrue(initialHandle.isOnline(), "handle must remain online during context creation");
    } finally {
      finishCreation.countDown();
      evaluationThread.join(10000);
      updateThread.join(10000);
    }
    evaluation.get(10, TimeUnit.SECONDS);
    update.get(10, TimeUnit.SECONDS);
    assertFalse(initialHandle.isOnline());
    verify(context).run(anyMap(), any(), any());
  }

  private static boolean blockedInHandleReplacement(Thread thread) {
    if (thread.getState() != Thread.State.WAITING) {
      return false;
    }
    for (StackTraceElement frame : thread.getStackTrace()) {
      if (frame.getClassName().equals(WAFModule.class.getName())
          && frame.getMethodName().equals("initOrUpdateWafHandle")) {
        return true;
      }
    }
    return false;
  }

  @Test
  void retriesWithoutWaitingForRetiredHandleDestruction() throws Exception {
    CountDownLatch captured = new CountDownLatch(1);
    CountDownLatch replaced = new CountDownLatch(1);
    when(request.isWafContextClosed())
        .thenAnswer(
            invocation -> {
              captured.countDown();
              assertTrue(replaced.await(10, TimeUnit.SECONDS));
              return false;
            });
    when(request.getOrCreateWafContext(any(), anyBoolean(), anyBoolean())).thenReturn(context);
    StampedLock oldLock = (StampedLock) currentSnapshotField("handleLock");
    FutureTask<Void> evaluation =
        new FutureTask<>(
            () -> {
              evaluate();
              return null;
            });
    FutureTask<Void> update =
        new FutureTask<>(
            () -> {
              reload();
              return null;
            });
    Thread evaluationThread = new Thread(evaluation, "waf-stale-snapshot-test");
    Thread updateThread = new Thread(update, "waf-retired-handle-test");
    long stamp = oldLock.writeLock();
    try {
      evaluationThread.start();
      assertTrue(captured.await(10, TimeUnit.SECONDS));
      updateThread.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (currentHandle() == initialHandle && !update.isDone() && System.nanoTime() < deadline) {
        Thread.yield();
      }
      assertTrue(currentHandle() != initialHandle, "replacement must be published");
      replaced.countDown();
      evaluation.get(10, TimeUnit.SECONDS);
      assertFalse(update.isDone(), "old-handle destruction is still blocked");
      verify(request)
          .getOrCreateWafContext(
              argThat(handle -> handle != initialHandle && handle.isOnline()),
              anyBoolean(),
              anyBoolean());
    } finally {
      replaced.countDown();
      oldLock.unlockWrite(stamp);
      evaluationThread.join(10000);
      updateThread.join(10000);
    }
    update.get(10, TimeUnit.SECONDS);
    assertFalse(initialHandle.isOnline());
  }

  @Test
  void usesAReadyContextWithoutTakingTheHandleLock() throws Exception {
    when(request.getWafContextIfReady(anyBoolean(), anyBoolean())).thenReturn(context);
    StampedLock handleLock = (StampedLock) currentSnapshotField("handleLock");
    FutureTask<Void> evaluation =
        new FutureTask<>(
            () -> {
              evaluate();
              return null;
            });
    long stamp = handleLock.writeLock();
    try {
      Thread thread = new Thread(evaluation, "waf-ready-context-test");
      thread.start();
      evaluation.get(10, TimeUnit.SECONDS);
    } finally {
      handleLock.unlockWrite(stamp);
    }

    verify(request, never()).getOrCreateWafContext(any(), anyBoolean(), anyBoolean());
    verify(context).run(anyMap(), any(), any());
  }

  @Test
  void doesNotCreateAContextForARequestClosedAfterTheClosedCheck() {
    AppSecRequestContext requestContext = spy(new AppSecRequestContext());
    evaluate(requestContext, new GatewayContext(false));
    assertNotNull(requestContext.getWafContextIfReady(true, false));
    // close between onDataAvailable's isWafContextClosed() check and the ready-context read
    doAnswer(
            invocation -> {
              Object open = invocation.callRealMethod();
              requestContext.closeWafContext();
              return open;
            })
        .when(requestContext)
        .isWafContextClosed();

    evaluate(requestContext, new GatewayContext(false));

    verify(requestContext, times(2)).getOrCreateWafContext(any(), anyBoolean(), anyBoolean());
    assertTrue(requestContext.isWafContextClosed());
    assertNull(requestContext.getWafContextIfReady(false, false));
    verify(requestContext, never()).setWafErrors();
  }

  @Test
  void skipsAReadyContextClosedBeforeItRuns() throws Exception {
    WafContext closed = new WafContext(currentHandle());
    closed.close();
    when(request.getWafContextIfReady(anyBoolean(), anyBoolean())).thenReturn(closed);
    // open at onDataAvailable's fast check, closed by the time the run fails
    when(request.isWafContextClosed()).thenReturn(false, true);

    evaluate();

    verify(request, never()).getOrCreateWafContext(any(), anyBoolean(), anyBoolean());
    verify(request, never()).setWafErrors();
  }

  @Test
  void firstRaspCallCreatesRaspMetricsThenUsesTheReadyContext() {
    AppSecRequestContext requestContext = spy(new AppSecRequestContext());
    GatewayContext rasp = new GatewayContext(false, RuleType.SQL_INJECTION);
    try {
      evaluate(requestContext, new GatewayContext(false));
      WafContext created = requestContext.getWafContextIfReady(true, false);
      assertNotNull(created);
      assertNull(requestContext.getRaspMetrics());

      evaluate(requestContext, rasp);
      evaluate(requestContext, rasp);

      verify(requestContext, times(1)).getOrCreateWafContext(any(), eq(true), eq(true));
      assertNotNull(requestContext.getRaspMetrics());
      assertEquals(2, requestContext.getRaspMetricsCounter().get());
      assertSame(created, requestContext.getWafContextIfReady(true, true));
    } finally {
      requestContext.closeWafContext();
    }
  }

  @Test
  void allowsReloadWhileAnExistingContextEvaluates() throws Exception {
    when(request.getOrCreateWafContext(any(), anyBoolean(), anyBoolean())).thenReturn(context);
    when(context.run(anyMap(), any(), any()))
        .thenAnswer(
            invocation -> {
              FutureTask<Void> update =
                  new FutureTask<>(
                      () -> {
                        reload();
                        return null;
                      });
              Thread thread = new Thread(update, "waf-reload-during-evaluation-test");
              thread.start();
              update.get(10, TimeUnit.SECONDS);
              assertFalse(initialHandle.isOnline());
              return Waf.ResultWithData.OK_NULL;
            });

    evaluate();

    verify(context).run(anyMap(), any(), any());
  }
}
