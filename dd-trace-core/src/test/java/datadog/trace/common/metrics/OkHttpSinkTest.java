package datadog.trace.common.metrics;

import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V06_METRICS_ENDPOINT;
import static datadog.trace.common.metrics.EventListener.EventType.OK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class OkHttpSinkTest {

  @TableTest({
    "scenario           | eventType   | responseCode",
    "404 -> DOWNGRADED  | DOWNGRADED  | 404         ",
    "500 -> ERROR       | ERROR       | 500         ",
    "0 throws -> ERROR  | ERROR       | 0           ",
    "400 -> BAD_PAYLOAD | BAD_PAYLOAD | 400         ",
    "200 -> OK          | OK          | 200         ",
    "201 -> OK          | OK          | 201         "
  })
  void httpStatusCodeResponseCodeYieldsEventType(
      EventListener.EventType eventType, int responseCode) {
    String agentUrl = "http://localhost:8126";
    EventListener listener = mock(EventListener.class);
    OkHttpClient client = mock(OkHttpClient.class);
    OkHttpSink sink =
        new OkHttpSink(client, agentUrl, V06_METRICS_ENDPOINT, true, false, Collections.emptyMap());
    sink.register(listener);

    doAnswer(invocation -> respond(invocation.getArgument(0), responseCode))
        .when(client)
        .newCall(any());
    sink.accept(0, ByteBuffer.allocate(0));

    verify(client, times(1)).newCall(any());
    verify(listener).onEvent(eq(eventType), any());
  }

  @Test
  void degradeToAsyncModeWhenAgentSlowToRespond() throws Exception {
    // metrics payloads are relatively large and we don't want to copy them,
    // and we typically expect the agent to respond well within the aggregation
    // window, so will send synchronously whenever possible to avoid allocating
    // a copy of the payload. When the agent is slow to respond, we degrade to
    // an asynchronous mode where up to 100 seconds of requests are copied and
    // enqueued for sending in the background, because we don't want to lose
    // them if it's possible not to.

    String agentUrl = "http://localhost:8126";
    CountDownLatch latch = new CountDownLatch(2);
    BlockingListener listener = new BlockingListener(latch);
    OkHttpClient client = mock(OkHttpClient.class);
    OkHttpSink sink =
        new OkHttpSink(client, agentUrl, V06_METRICS_ENDPOINT, true, false, Collections.emptyMap());
    sink.register(listener);
    // Single doAnswer handles all three calls using an atomic counter
    AtomicInteger callCount = new AtomicInteger(0);
    doAnswer(
            invocation -> {
              int callNumber = callCount.incrementAndGet();
              Request request = invocation.getArgument(0);
              if (callNumber == 1) {
                // First call: simulate slow agent
                Thread.sleep(1001);
              } else if (callNumber == 2) {
                // Second call: should be in degraded mode
                assertTrue(sink.isInDegradedMode());
              }
              return respond(request, 200);
            })
        .when(client)
        .newCall(any());

    // one slow response followed by a request
    sink.accept(1, ByteBuffer.allocate(0));
    sink.accept(1, ByteBuffer.allocate(0));
    latch.await();

    // the second request degrades to async mode
    verify(client, times(2)).newCall(any());
    assertEquals(2, listener.events.size());
    for (EventListener.EventType eventType : listener.events) {
      assertEquals(OK, eventType);
    }
    long asyncRequests = sink.asyncRequestCount();
    assertEquals(1, asyncRequests);
    assertTrue(sink.isInDegradedMode());

    // the agent has recovered and has responded quickly once
    sink.accept(1, ByteBuffer.allocate(0));

    // the request was sent synchronously
    verify(client, times(3)).newCall(any());
    assertEquals(asyncRequests, sink.asyncRequestCount());
    assertFalse(sink.isInDegradedMode());
  }

  private static Call respond(Request request, int code) throws IOException {
    if (code == 0) {
      return error();
    }
    Response response =
        new Response.Builder()
            .code(code)
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .message("message")
            .body(ResponseBody.create(MediaType.get("text/plain"), "message"))
            .build();
    Call call = mock(Call.class);
    doReturn(response).when(call).execute();
    return call;
  }

  private static Call error() throws IOException {
    Call call = mock(Call.class);
    doThrow(new IOException("thrown by test")).when(call).execute();
    return call;
  }

  private static class BlockingListener implements EventListener {

    private final CountDownLatch latch;
    final List<EventType> events = new CopyOnWriteArrayList<>();

    BlockingListener(CountDownLatch latch) {
      this.latch = latch;
    }

    @Override
    public void onEvent(EventType eventType, String message) {
      events.add(eventType);
      latch.countDown();
    }
  }
}
