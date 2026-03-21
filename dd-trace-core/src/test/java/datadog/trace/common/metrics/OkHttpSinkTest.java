package datadog.trace.common.metrics;

import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V06_METRICS_ENDPOINT;
import static datadog.trace.common.metrics.EventListener.EventType.BAD_PAYLOAD;
import static datadog.trace.common.metrics.EventListener.EventType.DOWNGRADED;
import static datadog.trace.common.metrics.EventListener.EventType.ERROR;
import static datadog.trace.common.metrics.EventListener.EventType.OK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OkHttpSinkTest {

  @Mock OkHttpClient client;

  static Stream<Arguments> httpStatusCodeYieldsEventTypeArguments() {
    return Stream.of(
        Arguments.of(DOWNGRADED, 404),
        Arguments.of(ERROR, 500),
        Arguments.of(ERROR, 0),
        Arguments.of(BAD_PAYLOAD, 400),
        Arguments.of(OK, 200),
        Arguments.of(OK, 201));
  }

  @ParameterizedTest
  @MethodSource("httpStatusCodeYieldsEventTypeArguments")
  void httpStatusCodeYieldsEventType(EventListener.EventType eventType, int responseCode)
      throws Exception {
    String agentUrl = "http://localhost:8126";
    String path = V06_METRICS_ENDPOINT;
    EventListener listener = Mockito.mock(EventListener.class);
    OkHttpSink sink = new OkHttpSink(client, agentUrl, path, true, false, Collections.emptyMap());
    sink.register(listener);

    Mockito.when(client.newCall(Mockito.any()))
        .thenAnswer(invocation -> respond((Request) invocation.getArgument(0), responseCode));

    sink.accept(0, ByteBuffer.allocate(0));

    Mockito.verify(listener).onEvent(Mockito.eq(eventType), Mockito.any());
  }

  @Test
  void degradeToAsyncModeWhenAgentSlowToRespond() throws Exception {
    String agentUrl = "http://localhost:8126";
    String path = V06_METRICS_ENDPOINT;
    CountDownLatch latch = new CountDownLatch(2);
    BlockingListener listener = new BlockingListener(latch);
    OkHttpSink sink = new OkHttpSink(client, agentUrl, path, true, false, Collections.emptyMap());
    sink.register(listener);
    AtomicBoolean first = new AtomicBoolean(true);

    Mockito.when(client.newCall(Mockito.any()))
        .thenAnswer(
            invocation -> {
              Request request = invocation.getArgument(0);
              if (first.compareAndSet(true, false)) {
                Thread.sleep(1001);
              } else {
                assertTrue(sink.isInDegradedMode());
              }
              return respond(request, 200);
            });

    sink.accept(1, ByteBuffer.allocate(0));
    sink.accept(1, ByteBuffer.allocate(0));
    latch.await();
    // Give the Sender thread's finally block time to update lastRequestTime after
    // onEvent() has triggered latch.countDown() — there is a brief window between
    // the countDown() call and the finally-block assignment.
    Thread.sleep(100);

    assertEquals(2, listener.events.size());
    for (EventListener.EventType et : listener.events) {
      assertEquals(OK, et);
    }
    long asyncRequests = sink.asyncRequestCount();
    assertEquals(1, asyncRequests);
    assertTrue(sink.isInDegradedMode());

    // Test recovery: lastRequestTime is now small (Sender processed the queued request quickly),
    // so the next accept() goes through the sync path and cancels the scheduled async task.
    sink.accept(1, ByteBuffer.allocate(0));
    assertEquals(asyncRequests, sink.asyncRequestCount());
    assertFalse(sink.isInDegradedMode());
  }

  private Call respond(Request request, int code) {
    if (code == 0) {
      return error(request);
    }
    Request responseRequest =
        request != null ? request : new Request.Builder().url("http://localhost/").build();
    Call call = Mockito.mock(Call.class);
    try {
      Mockito.when(call.execute())
          .thenReturn(
              new Response.Builder()
                  .code(code)
                  .request(responseRequest)
                  .protocol(Protocol.HTTP_1_1)
                  .message("message")
                  .body(ResponseBody.create(MediaType.get("text/plain"), "message"))
                  .build());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return call;
  }

  private Call error(Request request) {
    Call call = Mockito.mock(Call.class);
    try {
      Mockito.when(call.execute()).thenThrow(new IOException("thrown by test"));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return call;
  }

  private static class BlockingListener implements EventListener {
    private final CountDownLatch latch;
    private List<EventType> events = new CopyOnWriteArrayList<>();

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
