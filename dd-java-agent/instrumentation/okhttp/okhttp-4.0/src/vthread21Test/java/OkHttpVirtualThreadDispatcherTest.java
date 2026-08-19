import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.core.DDSpan;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * End-to-end reproduction of profiling-backend PR#8520: swap OkHttp's Dispatcher executor from
 * {@code Executors.newCachedThreadPool(...)} to {@code
 * Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(prefix, start).factory())}.
 *
 * <p>Each test runs against the same {@link HttpServer} mock. Inside a manually activated "parent"
 * span it does {@code client.newCall(request).enqueue(callback)} and waits on a latch for the
 * callback. The agent's OkHttp instrumentation injects {@code TracingInterceptor}, which creates
 * the {@code okhttp.request} client span using whatever scope is active on the dispatcher worker.
 * The assertions verify the client span lands under the parent &mdash; i.e., the dispatcher's
 * worker thread saw the propagated scope.
 *
 * <p>{@code concurrentVirtualThreadPerTaskDispatcher_keepsEachTraceSeparate} is the regression test
 * for the fix: it forces dispatcher-queue contention so calls are promoted from {@code
 * Dispatcher.finished()} on a sibling's worker thread, and fails (cross-trace contamination)
 * without the {@code AsyncCall.<init>} scope capture. {@code virtualThreadPerTaskDispatcher_…} is a
 * single-shot baseline: it confirms basic propagation through the virtual-thread dispatcher but
 * does not by itself exercise the contamination path (single-shot requests never queue).
 */
class OkHttpVirtualThreadDispatcherTest extends AbstractInstrumentationTest {

  private static HttpServer mockServer;
  private static String baseUrl;

  @BeforeAll
  static void startServer() throws IOException {
    mockServer = HttpServer.create(new InetSocketAddress("localhost", 0), 64);
    mockServer.createContext(
        "/ok",
        exchange -> {
          byte[] body = "ok".getBytes();
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    // Default executor is single-threaded — give the server real concurrency so the test isn't
    // bottlenecked on the mock backend itself.
    mockServer.setExecutor(Executors.newCachedThreadPool());
    mockServer.start();
    baseUrl =
        "http://"
            + mockServer.getAddress().getHostString()
            + ":"
            + mockServer.getAddress().getPort();
  }

  @AfterAll
  static void stopServer() {
    if (mockServer != null) {
      mockServer.stop(0);
      mockServer = null;
    }
  }

  /** Activate a manual parent span, run the OkHttp call, wait for the okhttp.request child. */
  private void runUnderParent(OkHttpClient client, CountDownLatch done) {
    AgentSpan parentSpan = AgentTracer.startSpan("test", "parent");
    try (AgentScope ignored = AgentTracer.activateSpan(parentSpan)) {
      Request request = new Request.Builder().url(baseUrl + "/ok").build();
      client
          .newCall(request)
          .enqueue(
              new Callback() {
                @Override
                public void onResponse(Call call, Response response) throws IOException {
                  response.body().close();
                  done.countDown();
                }

                @Override
                public void onFailure(Call call, IOException e) {
                  done.countDown();
                }
              });
      if (!done.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("timed out waiting for OkHttp callback");
      }
      // Wait for the okhttp.request child to finish before closing the parent scope so the trace
      // collector sees a complete trace.
      blockUntilChildSpansFinished(1);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    } finally {
      parentSpan.finish();
    }
  }

  @Test
  void virtualThreadPerTaskDispatcher_parentsOkHttpSpanUnderParent() throws Exception {
    // Exact shape from profiling-backend PR#8520.
    ExecutorService dispatcherExecutor =
        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("okhttp-test-", 0).factory());
    OkHttpClient client = buildClient(dispatcherExecutor);
    try {
      runUnderParent(client, new CountDownLatch(1));
    } finally {
      dispatcherExecutor.shutdown();
    }

    assertOkHttpSpanParentedUnderParent();
  }

  /**
   * Concurrent stress test: spin up N independent parent traces, each enqueueing M OkHttp requests
   * through the same virtual-thread dispatcher. Dispatcher capacity is intentionally set below N*M
   * so that some calls get queued and then promoted from {@code Dispatcher.finished()} running on a
   * dispatcher worker thread (a different parent's worker).
   *
   * <p>If the agent captures the worker's currently-active scope when the promoted call is
   * submitted &mdash; instead of the scope active where the original {@code enqueue()} happened
   * &mdash; the {@code okhttp.request} span will land in the wrong parent's trace. This test fails
   * loudly on that cross-contamination.
   */
  @Test
  void concurrentVirtualThreadPerTaskDispatcher_keepsEachTraceSeparate() throws Exception {
    int parentCount = 16;
    int requestsPerParent = 4;
    int totalRequests = parentCount * requestsPerParent;

    ExecutorService dispatcherExecutor =
        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("okhttp-burst-", 0).factory());
    OkHttpClient client = buildClient(dispatcherExecutor);
    // Force queue contention: capacity is much smaller than the total in-flight requests, so
    // many calls will be promoted from finished() rather than direct from enqueue().
    Dispatcher dispatcher = client.dispatcher();
    dispatcher.setMaxRequests(4);
    dispatcher.setMaxRequestsPerHost(4);

    ExecutorService parentRunner = Executors.newFixedThreadPool(parentCount);
    CountDownLatch allParentsDone = new CountDownLatch(parentCount);
    AtomicReference<Throwable> failure = new AtomicReference<>();

    try {
      for (int i = 0; i < parentCount; i++) {
        parentRunner.submit(
            () -> {
              try {
                runParentBurst(client, requestsPerParent);
              } catch (Throwable t) {
                failure.compareAndSet(null, t);
              } finally {
                allParentsDone.countDown();
              }
            });
      }
      assertTrue(allParentsDone.await(60, TimeUnit.SECONDS), "parent threads timed out");
    } finally {
      parentRunner.shutdown();
      dispatcherExecutor.shutdown();
    }

    if (failure.get() != null) {
      throw new AssertionError("a parent-runner thread threw", failure.get());
    }

    // Each parent produces one trace containing 1 parent span + M okhttp.request spans.
    writer.waitForTraces(parentCount);

    // Map trace-id -> spans-in-that-trace and assert structure.
    Map<Long, List<DDSpan>> tracesByRoot = new HashMap<>();
    for (List<DDSpan> trace : writer) {
      DDSpan parentSpan = findByOp(trace, "parent");
      assertNotNull(parentSpan, "every collected trace should have a parent span");
      tracesByRoot.put(parentSpan.getSpanId(), trace);
    }
    assertEquals(
        parentCount,
        tracesByRoot.size(),
        "expected one distinct trace per parent burst (no cross-trace contamination)");

    int totalOkhttpSpans = 0;
    List<String> contamination = new ArrayList<>();
    for (Map.Entry<Long, List<DDSpan>> entry : tracesByRoot.entrySet()) {
      long parentSpanId = entry.getKey();
      List<DDSpan> trace = entry.getValue();
      int okhttpCountInThisTrace = 0;
      for (DDSpan span : trace) {
        if ("okhttp.request".contentEquals(span.getOperationName())) {
          okhttpCountInThisTrace++;
          if (span.getParentId() != parentSpanId) {
            contamination.add(
                "okhttp.request span "
                    + span.getSpanId()
                    + " has parentId="
                    + span.getParentId()
                    + " but it lives in the trace rooted at "
                    + parentSpanId);
          }
        }
      }
      totalOkhttpSpans += okhttpCountInThisTrace;
      assertEquals(
          requestsPerParent,
          okhttpCountInThisTrace,
          "trace rooted at parent " + parentSpanId + " has wrong child count");
    }

    assertEquals(totalRequests, totalOkhttpSpans, "total okhttp.request spans across all traces");
    assertTrue(
        contamination.isEmpty(),
        "found cross-parented okhttp.request spans:\n  - " + String.join("\n  - ", contamination));
  }

  /**
   * One parent burst: M OkHttp requests under a single freshly-started parent span. Deliberately
   * does <em>not</em> block on per-parent child-span accounting &mdash; the whole point of the test
   * is to detect when children leak to a sibling's trace, and per-parent blocking would just turn
   * that into a timeout instead of producing a useful assertion message. Wait for the HTTP
   * callbacks (so the request actually ran), then close the parent.
   */
  private void runParentBurst(OkHttpClient client, int requestsPerParent) {
    AgentSpan parentSpan = AgentTracer.startSpan("test", "parent");
    try (AgentScope ignored = AgentTracer.activateSpan(parentSpan)) {
      CountDownLatch done = new CountDownLatch(requestsPerParent);
      for (int i = 0; i < requestsPerParent; i++) {
        Request request = new Request.Builder().url(baseUrl + "/ok").build();
        client
            .newCall(request)
            .enqueue(
                new Callback() {
                  @Override
                  public void onResponse(Call call, Response response) throws IOException {
                    response.body().close();
                    done.countDown();
                  }

                  @Override
                  public void onFailure(Call call, IOException e) {
                    done.countDown();
                  }
                });
      }
      if (!done.await(30, TimeUnit.SECONDS)) {
        throw new AssertionError("timed out waiting for OkHttp callbacks");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    } finally {
      parentSpan.finish();
    }
  }

  private static OkHttpClient buildClient(ExecutorService dispatcherExecutor) {
    Dispatcher dispatcher = new Dispatcher(dispatcherExecutor);
    return new OkHttpClient.Builder().dispatcher(dispatcher).build();
  }

  private void assertOkHttpSpanParentedUnderParent() throws Exception {
    writer.waitForTraces(1);
    List<DDSpan> trace = writer.get(0);
    DDSpan parentSpan = findByOp(trace, "parent");
    DDSpan okhttpSpan = findByOp(trace, "okhttp.request");
    assertNotNull(parentSpan, "parent span should exist");
    assertNotNull(
        okhttpSpan,
        "okhttp.request client span should exist; if missing, propagation may have produced an"
            + " orphan trace instead");
    assertEquals(
        parentSpan.getTraceId().toLong(),
        okhttpSpan.getTraceId().toLong(),
        "okhttp.request span must share the parent's trace id");
    assertEquals(
        parentSpan.getSpanId(),
        okhttpSpan.getParentId(),
        "okhttp.request span must be parented under the parent span active at enqueue() time");
  }

  private static DDSpan findByOp(List<DDSpan> spans, String op) {
    return spans.stream()
        .filter(s -> op.contentEquals(s.getOperationName()))
        .findFirst()
        .orElse(null);
  }
}
